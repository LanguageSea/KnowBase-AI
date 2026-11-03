package com.knowbase.ingest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * MySQL 原子任务队列。候选行通过带 claimToken 的条件 UPDATE 认领，
 * 必须影响 1 行才算成功；两个 Java 实例并发时同一条任务只会执行一次。
 */
@Repository
public class IngestionTaskRepository {

    public enum TaskType {
        INGEST, DELETE
    }

    private final JdbcTemplate jdbcTemplate;

    public IngestionTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertQueued(long docId, TaskType type, int maxAttempt, LocalDateTime now) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO kb_ingestion_task (doc_id, type, status, attempt, max_attempt,"
                            + " created_at, updated_at) VALUES (?, ?, 'QUEUED', 0, ?, ?, ?)",
                    new String[]{"id"});
            ps.setLong(1, docId);
            ps.setString(2, type.name());
            ps.setInt(3, maxAttempt);
            ps.setObject(4, now);
            ps.setObject(5, now);
            return ps;
        }, keys);
        Number id = keys.getKey();
        if (id == null) {
            throw new IllegalStateException("创建任务后未返回主键");
        }
        return id.longValue();
    }

    public long countNonTerminal(long docId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_ingestion_task WHERE doc_id = ?"
                        + " AND status IN ('QUEUED','RUNNING')",
                Long.class, docId);
        return count == null ? 0 : count;
    }

    public Optional<ClaimedTask> claimNext(TaskType type, String workerId) {
        String documentStatuses = type == TaskType.INGEST
                ? "d.status IN ('PENDING','FAILED')"
                : "d.status IN ('DELETING','DELETE_FAILED')";
        LocalDateTime now = LocalDateTime.now();
        String claimToken = workerId + "-" + UUID.randomUUID().toString().substring(0, 8);
        int changed = jdbcTemplate.update(
                "UPDATE kb_ingestion_task SET status = 'RUNNING', locked_by = ?, locked_at = ?,"
                        + " started_at = ?, attempt = attempt + 1, error_code = NULL,"
                        + " error_msg = NULL, updated_at = ?, finished_at = NULL"
                        + " WHERE id = (SELECT candidate.id FROM ("
                        + "   SELECT t.id FROM kb_ingestion_task t"
                        + "   JOIN kb_document d ON d.id = t.doc_id"
                        + "   WHERE t.type = ? AND t.status = 'QUEUED' AND t.attempt < t.max_attempt"
                        + "   AND " + documentStatuses
                        + "   ORDER BY t.created_at, t.id LIMIT 1"
                        + " ) candidate)"
                        + " AND status = 'QUEUED' AND attempt < max_attempt",
                claimToken, now, now, now, type.name());
        if (changed != 1) {
            return Optional.empty();
        }
        List<TaskCandidate> candidates = jdbcTemplate.query(
                "SELECT id, doc_id, attempt, max_attempt FROM kb_ingestion_task"
                        + " WHERE locked_by = ? AND status = 'RUNNING' ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> new TaskCandidate(
                        rs.getLong("id"), rs.getLong("doc_id"),
                        rs.getInt("attempt"), rs.getInt("max_attempt")),
                claimToken);
        if (candidates.isEmpty()) {
            throw new IllegalStateException("任务认领成功但无法读取认领结果");
        }
        TaskCandidate candidate = candidates.get(0);
        int documentChanged = jdbcTemplate.update(
                "UPDATE kb_document SET status = ?, updated_at = ?"
                        + " WHERE id = ? AND " + documentStatuses.replace("d.", ""),
                type == TaskType.INGEST ? "PROCESSING" : "DELETING",
                now, candidate.docId());
        if (documentChanged != 1) {
            jdbcTemplate.update(
                    "UPDATE kb_ingestion_task SET status = 'FAILED',"
                            + " error_code = 'DOCUMENT_STATE_CHANGED',"
                            + " error_msg = '文档状态已变化，任务放弃执行', finished_at = ?,"
                            + " locked_by = NULL, locked_at = NULL, updated_at = ?"
                            + " WHERE id = ? AND status = 'RUNNING'",
                    now, now, candidate.id());
            return Optional.empty();
        }
        return Optional.of(new ClaimedTask(
                candidate.id(), candidate.docId(), type,
                candidate.attempt(), candidate.maxAttempt()));
    }

    public void markSuccess(long taskId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE kb_ingestion_task SET status = 'SUCCESS', finished_at = ?,"
                        + " locked_at = NULL, updated_at = ? WHERE id = ? AND status = 'RUNNING'",
                now, now, taskId);
    }

    public void markFailure(ClaimedTask task, String errorCode, String errorMsg, LocalDateTime now) {
        String terminalStatus = task.attempt() >= task.maxAttempt() ? "DEAD" : "FAILED";
        String docStatus = task.type() == TaskType.INGEST ? "FAILED" : "DELETE_FAILED";
        String message = errorMsg == null ? "任务执行失败"
                : errorMsg.substring(0, Math.min(errorMsg.length(), 2000));
        jdbcTemplate.update(
                "UPDATE kb_ingestion_task SET status = ?, error_code = ?, error_msg = ?,"
                        + " finished_at = ?, locked_at = NULL, updated_at = ?"
                        + " WHERE id = ? AND status = 'RUNNING'",
                terminalStatus, truncate(errorCode, 64), message, now, now, task.id());
        jdbcTemplate.update(
                "UPDATE kb_document SET status = ?, error_code = ?, error_msg = ?, updated_at = ?"
                        + " WHERE id = ? AND status IN ('PROCESSING','DELETING')",
                docStatus, truncate(errorCode, 64), message, now, task.docId());
    }

    public void markCancelled(long taskId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE kb_ingestion_task SET status = 'FAILED', error_code = 'TASK_CANCELLED',"
                        + " error_msg = '文档状态已变化，本次处理结果被丢弃', finished_at = ?,"
                        + " locked_at = NULL, updated_at = ? WHERE id = ? AND status = 'RUNNING'",
                now, now, taskId);
    }

    /**
     * RUNNING 超过 10 分钟视为僵尸。attempt 已在使用时自增，因此达到上限直接 DEAD，
     * 否则回 QUEUED，等待任一实例重新认领。
     */
    public int recoverStale(LocalDateTime before, LocalDateTime now) {
        List<StaleTask> stale = jdbcTemplate.query(
                "SELECT id, doc_id, type, attempt, max_attempt FROM kb_ingestion_task"
                        + " WHERE status = 'RUNNING' AND locked_at < ?",
                (rs, rowNum) -> new StaleTask(
                        rs.getLong("id"), rs.getLong("doc_id"), rs.getString("type"),
                        rs.getInt("attempt"), rs.getInt("max_attempt")),
                before);
        int recovered = 0;
        for (StaleTask task : stale) {
            boolean dead = task.attempt() >= task.maxAttempt();
            String status = dead ? "DEAD" : "QUEUED";
            int changed = jdbcTemplate.update(
                    "UPDATE kb_ingestion_task SET status = ?, locked_by = NULL, locked_at = NULL,"
                            + " error_code = 'TASK_TIMEOUT', error_msg = '任务执行超时，已由调度器回收',"
                            + " updated_at = ? WHERE id = ? AND status = 'RUNNING'",
                    status, now, task.id());
            if (changed != 1) {
                continue;
            }
            String docStatus = "DELETE".equals(task.type())
                    ? (dead ? "DELETE_FAILED" : "DELETING")
                    : (dead ? "FAILED" : "PENDING");
            jdbcTemplate.update(
                    "UPDATE kb_document SET status = ?, error_code = 'TASK_TIMEOUT',"
                            + " error_msg = '任务执行超时，已由调度器回收', updated_at = ? WHERE id = ?",
                    docStatus, now, task.docId());
            recovered++;
        }
        return recovered;
    }

    public int requeueFailedDeletes(LocalDateTime now) {
        return jdbcTemplate.update(
                "UPDATE kb_ingestion_task SET status = 'QUEUED', error_code = NULL, error_msg = NULL,"
                        + " locked_by = NULL, locked_at = NULL, updated_at = ?"
                        + " WHERE type = 'DELETE' AND status = 'FAILED' AND attempt < max_attempt",
                now);
    }

    public Optional<TaskSnapshot> findLatest(long docId, TaskType type) {
        List<TaskSnapshot> rows = jdbcTemplate.query(
                "SELECT id, doc_id, type, status, attempt, max_attempt, error_code, error_msg"
                        + " FROM kb_ingestion_task WHERE doc_id = ? AND type = ?"
                        + " ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> new TaskSnapshot(
                        rs.getLong("id"), rs.getLong("doc_id"), rs.getString("type"),
                        rs.getString("status"), rs.getInt("attempt"), rs.getInt("max_attempt"),
                        rs.getString("error_code"), rs.getString("error_msg")),
                docId, type.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean requeueForRetry(long docId, TaskType type, LocalDateTime now) {
        int changed = jdbcTemplate.update(
                "UPDATE kb_ingestion_task t JOIN ("
                        + " SELECT id FROM kb_ingestion_task WHERE doc_id = ? AND type = ?"
                        + " AND status = 'FAILED' AND attempt < max_attempt ORDER BY id DESC LIMIT 1"
                        + ") latest ON latest.id = t.id"
                        + " SET t.status = 'QUEUED', t.error_code = NULL, t.error_msg = NULL,"
                        + " t.finished_at = NULL, t.locked_by = NULL, t.locked_at = NULL,"
                        + " t.updated_at = ? WHERE t.status = 'FAILED'",
                docId, type.name(), now);
        return changed == 1;
    }

    public int cancelQueuedOrRunning(long docId, LocalDateTime now) {
        return jdbcTemplate.update(
                "UPDATE kb_ingestion_task SET status = 'FAILED', error_code = 'DOCUMENT_DELETING',"
                        + " error_msg = '文档正在删除，任务已取消', finished_at = ?,"
                        + " locked_by = NULL, locked_at = NULL, updated_at = ?"
                        + " WHERE doc_id = ? AND status IN ('QUEUED','RUNNING')",
                now, now, docId);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    record TaskCandidate(long id, long docId, int attempt, int maxAttempt) {
    }

    private record StaleTask(long id, long docId, String type, int attempt, int maxAttempt) {
    }

    public record ClaimedTask(long id, long docId, TaskType type, int attempt, int maxAttempt) {
    }

    public record TaskSnapshot(long id, long docId, String type, String status, int attempt,
            int maxAttempt, String errorCode, String errorMsg) {
    }
}
