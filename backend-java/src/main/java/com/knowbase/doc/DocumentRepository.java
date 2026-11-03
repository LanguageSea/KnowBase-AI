package com.knowbase.doc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 文档与分片数据访问（DDL 见 V1__init.sql §9/§10）。
 *
 * <p>去重靠 uk_doc_dedup(kb_id, dedup_key)：ACTIVE 时 dedup_key=sha256，
 * 命中抛 DuplicateKeyException，上层转 409。分片写入先整批删再插，
 * 保证重跑幂等（seq 从 0 连续，uk_chunk_doc_seq 兜底）。
 */
@Repository
public class DocumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入文档行（状态 PENDING，等待 Python 处理）。
     *
     * @throws DuplicateKeyException 同库同内容重复上传
     */
    public long insertPending(long kbId, String name, String ext, long sizeBytes,
            String sha256, String storageKey, int uploadedBy, LocalDateTime now) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO kb_document (kb_id, name, ext, size_bytes, sha256, dedup_key,"
                            + " storage_key, status, chunk_count, uploaded_by, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)",
                    new String[] {"id"});
            ps.setLong(1, kbId);
            ps.setString(2, name);
            ps.setString(3, ext);
            ps.setLong(4, sizeBytes);
            ps.setString(5, sha256);
            ps.setString(6, sha256);
            ps.setString(7, storageKey);
            ps.setInt(8, uploadedBy);
            ps.setObject(9, now);
            ps.setObject(10, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("插入文档后未返回主键");
        }
        return key.longValue();
    }

    /** 处理成功：ACTIVE + chunk_count + indexed_at。 */
    public void markActive(long docId, int chunkCount, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE kb_document SET status = 'ACTIVE', chunk_count = ?,"
                        + " indexed_at = ?, error_code = NULL, error_msg = NULL,"
                        + " updated_at = ? WHERE id = ?",
                chunkCount, now, now, docId);
    }

    /** 仅允许处于处理中的文档回写成功，删除竞态下 affected rows 为 0。 */
    public int markActiveIfProcessing(long docId, int chunkCount, LocalDateTime now) {
        return jdbcTemplate.update(
                "UPDATE kb_document SET status = 'ACTIVE', chunk_count = ?,"
                        + " indexed_at = ?, error_code = NULL, error_msg = NULL,"
                        + " updated_at = ? WHERE id = ? AND status = 'PROCESSING'",
                chunkCount, now, now, docId);
    }

    /** 处理失败：FAILED + error_code/error_msg，接口仍回 200。 */
    public void markFailed(long docId, String errorCode, String errorMsg, LocalDateTime now) {
        String truncated = errorMsg == null ? null
                : errorMsg.substring(0, Math.min(errorMsg.length(), 2000));
        jdbcTemplate.update(
                "UPDATE kb_document SET status = 'FAILED', error_code = ?,"
                        + " error_msg = ?, updated_at = ? WHERE id = ?",
                errorCode, truncated, now, docId);
    }

    /** 按 id 取文档行。 */
    public Optional<DocumentRow> findById(long docId) {
        List<DocumentRow> rows = jdbcTemplate.query(
                "SELECT id, kb_id, name, ext, size_bytes, sha256, storage_key, status,"
                        + " chunk_count, error_code, error_msg, indexed_at, created_at"
                        + " FROM kb_document WHERE id = ?",
                (rs, rowNum) -> new DocumentRow(
                        rs.getLong("id"), rs.getLong("kb_id"), rs.getString("name"),
                        rs.getString("ext"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("storage_key"), rs.getString("status"),
                        rs.getInt("chunk_count"), rs.getString("error_code"),
                        rs.getString("error_msg"), rs.getString("indexed_at"),
                        rs.getString("created_at")),
                docId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 指定库文档列表（排除 DELETED），按 created_at 倒序。 */
    public List<DocumentRow> listByKb(long kbId) {
        return jdbcTemplate.query(
                "SELECT id, kb_id, name, ext, size_bytes, sha256, storage_key, status,"
                        + " chunk_count, error_code, error_msg, indexed_at, created_at"
                        + " FROM kb_document WHERE kb_id = ? AND status <> 'DELETED'"
                        + " ORDER BY created_at DESC",
                (rs, rowNum) -> new DocumentRow(
                        rs.getLong("id"), rs.getLong("kb_id"), rs.getString("name"),
                        rs.getString("ext"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("storage_key"), rs.getString("status"),
                        rs.getInt("chunk_count"), rs.getString("error_code"),
                        rs.getString("error_msg"), rs.getString("indexed_at"),
                        rs.getString("created_at")),
                kbId);
    }

    public boolean isStatus(long docId, String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document WHERE id = ? AND status = ?",
                Long.class, docId, status);
        return count != null && count > 0;
    }

    public int markDeleting(long docId, LocalDateTime now) {
        return jdbcTemplate.update(
                "UPDATE kb_document SET status = 'DELETING', error_code = NULL, error_msg = NULL,"
                        + " updated_at = ? WHERE id = ?"
                        + " AND status IN ('PENDING','PROCESSING','ACTIVE','FAILED','DELETE_FAILED')",
                now, docId);
    }

    public void markDeleteFailed(long docId, String errorCode, String errorMsg, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE kb_document SET status = 'DELETE_FAILED', error_code = ?, error_msg = ?,"
                        + " updated_at = ? WHERE id = ? AND status IN ('DELETING','DELETE_FAILED')",
                errorCode, truncate(errorMsg, 2000), now, docId);
    }

    public void markDeleted(long docId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE kb_document SET status = 'DELETED', dedup_key = NULL, chunk_count = 0,"
                        + " error_code = NULL, error_msg = NULL, updated_at = ?"
                        + " WHERE id = ? AND status IN ('DELETING','DELETE_FAILED')",
                now, docId);
    }

    public void clearFailureForRetry(long docId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE kb_document SET status = 'PENDING', error_code = NULL, error_msg = NULL,"
                        + " updated_at = ? WHERE id = ? AND status = 'FAILED'",
                now, docId);
    }

    public List<ChunkSnapshot> listChunks(long docId) {
        return jdbcTemplate.query(
                "SELECT id, seq, content, page_no, heading, token_count, vector_id, created_at"
                        + " FROM kb_document_chunk WHERE doc_id = ? ORDER BY seq",
                (rs, rowNum) -> new ChunkSnapshot(
                        rs.getLong("id"), rs.getInt("seq"), rs.getString("content"),
                        (Integer) rs.getObject("page_no"), rs.getString("heading"),
                        (Integer) rs.getObject("token_count"), rs.getString("vector_id"),
                        rs.getString("created_at")),
                docId);
    }

    public Optional<ChunkSnapshot> findChunk(long docId, int seq) {
        List<ChunkSnapshot> rows = jdbcTemplate.query(
                "SELECT id, seq, content, page_no, heading, token_count, vector_id, created_at"
                        + " FROM kb_document_chunk WHERE doc_id = ? AND seq = ?",
                (rs, rowNum) -> new ChunkSnapshot(
                        rs.getLong("id"), rs.getInt("seq"), rs.getString("content"),
                        (Integer) rs.getObject("page_no"), rs.getString("heading"),
                        (Integer) rs.getObject("token_count"), rs.getString("vector_id"),
                        rs.getString("created_at")),
                docId, seq);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void deleteChunks(long docId) {
        jdbcTemplate.update("DELETE FROM kb_document_chunk WHERE doc_id = ?", docId);
    }

    public void deleteCitationsForDocument(long docId) {
        jdbcTemplate.update(
                "DELETE c FROM ai_message_citation c"
                        + " JOIN kb_document_chunk ch ON ch.id = c.chunk_id WHERE ch.doc_id = ?",
                docId);
    }

    public long countReferencedChunks(long docId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_message_citation c"
                        + " JOIN kb_document_chunk ch ON ch.id = c.chunk_id WHERE ch.doc_id = ?",
                Long.class, docId);
        return count == null ? 0 : count;
    }

    /** 整批替换分片：先删再插，重跑幂等。 */
    public void replaceChunks(long docId, List<ChunkRow> chunks, LocalDateTime now) {
        jdbcTemplate.update("DELETE FROM kb_document_chunk WHERE doc_id = ?", docId);
        for (ChunkRow chunk : chunks) {
            jdbcTemplate.update(
                    "INSERT INTO kb_document_chunk (doc_id, seq, content, page_no, heading,"
                            + " token_count, vector_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    docId, chunk.seq(), chunk.content(), chunk.pageNo(), chunk.heading(),
                    chunk.tokenCount(), chunk.vectorId(), now);
        }
    }

    /** (doc_id, seq) -> chunk id（问答落引用时映射用）。 */
    public Long findChunkId(long docId, int seq) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT id FROM kb_document_chunk WHERE doc_id = ? AND seq = ?",
                Long.class, docId, seq);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 文档行。 */
    public record DocumentRow(long id, long kbId, String name, String ext, long sizeBytes,
            String sha256, String storageKey, String status, int chunkCount,
            String errorCode, String errorMsg, String indexedAt, String createdAt) {
    }

    /** 分片行（vector_id 用 uuid5(doc_id,seq) 确定性生成，重跑覆盖同一批 point）。 */
    public record ChunkRow(int seq, String content, Integer pageNo, String heading,
            Integer tokenCount, String vectorId) {
    }

    public record ChunkSnapshot(long id, int seq, String content, Integer pageNo, String heading,
            Integer tokenCount, String vectorId, String createdAt) {
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
