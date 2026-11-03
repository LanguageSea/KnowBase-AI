package com.knowbase.workspace;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class WorkspaceRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countAccessibleDocuments(int userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document d"
                        + " JOIN kb_knowledge_base k ON k.id = d.kb_id"
                        + " WHERE d.status = 'ACTIVE' AND k.status = 'ACTIVE'"
                        + " AND (k.owner_id = ? OR EXISTS ("
                        + "   SELECT 1 FROM kb_member m WHERE m.kb_id = k.id AND m.user_id = ?))",
                Long.class, userId, userId);
        return count == null ? 0 : count;
    }

    public long countProcessingDocuments(int userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document d"
                        + " JOIN kb_knowledge_base k ON k.id = d.kb_id"
                        + " WHERE d.status IN ('PENDING','PROCESSING','FAILED','DELETING','DELETE_FAILED')"
                        + " AND k.status = 'ACTIVE'"
                        + " AND (k.owner_id = ? OR EXISTS ("
                        + "   SELECT 1 FROM kb_member m WHERE m.kb_id = k.id AND m.user_id = ?))",
                Long.class, userId, userId);
        return count == null ? 0 : count;
    }

    public long countConversations(int userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_conversation WHERE user_id = ? AND status = 'ACTIVE'",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    public long countQuestions(int userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_message msg"
                        + " JOIN ai_conversation c ON c.id = msg.conversation_id"
                        + " WHERE c.user_id = ? AND msg.role = 'USER'",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    public List<DailyQuestionRow> countQuestionsByDay(int userId, LocalDateTime since) {
        return jdbcTemplate.query(
                "SELECT DATE_FORMAT(msg.created_at, '%Y-%m-%d') AS day, COUNT(*) AS question_count"
                        + " FROM ai_message msg JOIN ai_conversation c ON c.id = msg.conversation_id"
                        + " WHERE c.user_id = ? AND msg.role = 'USER' AND msg.created_at >= ?"
                        + " GROUP BY day ORDER BY day",
                (rs, rowNum) -> new DailyQuestionRow(
                        rs.getString("day"), rs.getLong("question_count")),
                userId, since);
    }

    public List<LatestDocumentRow> latestDocuments(int userId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return jdbcTemplate.query(
                "SELECT d.id, d.kb_id, k.name AS kb_name, d.name, d.status, d.created_at"
                        + " FROM kb_document d JOIN kb_knowledge_base k ON k.id = d.kb_id"
                        + " WHERE d.status <> 'DELETED' AND k.status = 'ACTIVE'"
                        + " AND (k.owner_id = ? OR EXISTS ("
                        + "   SELECT 1 FROM kb_member m WHERE m.kb_id = k.id AND m.user_id = ?))"
                        + " ORDER BY d.created_at DESC, d.id DESC LIMIT " + safeLimit,
                (rs, rowNum) -> new LatestDocumentRow(
                        rs.getLong("id"), rs.getLong("kb_id"), rs.getString("kb_name"),
                        rs.getString("name"), rs.getString("status"), rs.getString("created_at")),
                userId, userId);
    }

    public record DailyQuestionRow(String day, long count) {
    }

    public record LatestDocumentRow(long id, long kbId, String kbName, String name,
            String status, String createdAt) {
    }
}
