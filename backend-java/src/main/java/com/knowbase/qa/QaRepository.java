package com.knowbase.qa;

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Conversation, message and citation persistence. Ownership is always part of
 * the query for reads used by public APIs, so another user's id resolves to an
 * empty result and is surfaced as 404 by the service layer.
 */
@Repository
public class QaRepository {

    private static final RowMapper<ConversationRow> CONVERSATION_MAPPER = (rs, rowNum) ->
            new ConversationRow(
                    rs.getLong("id"),
                    rs.getInt("user_id"),
                    rs.getString("title"),
                    rs.getString("status"),
                    rs.getString("created_at"),
                    rs.getString("updated_at"));

    private static final RowMapper<ConversationSummaryRow> SUMMARY_MAPPER = (rs, rowNum) ->
            new ConversationSummaryRow(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getString("last_message"),
                    rs.getInt("message_count"),
                    rs.getString("created_at"),
                    rs.getString("updated_at"));

    private static final RowMapper<MessageRow> MESSAGE_MAPPER = (rs, rowNum) -> new MessageRow(
            rs.getLong("id"),
            rs.getLong("conversation_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("status"),
            rs.getString("model_name"),
            (Integer) rs.getObject("latency_ms"),
            rs.getString("error_code"),
            rs.getString("created_at"));

    private static final RowMapper<CitationRow> CITATION_MAPPER = (rs, rowNum) -> new CitationRow(
            rs.getLong("id"),
            rs.getLong("message_id"),
            rs.getLong("chunk_id"),
            rs.getLong("doc_id"),
            rs.getString("doc_name"),
            rs.getLong("kb_id"),
            rs.getInt("rank_no"),
            rs.getInt("chunk_seq"),
            rs.getObject("score") == null ? null : rs.getDouble("score"),
            rs.getString("quote"),
            rs.getString("doc_status"));

    private final JdbcTemplate jdbcTemplate;

    public QaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insertConversation(int userId, String title, LocalDateTime now) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        String shortTitle = normalizeTitle(title);
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO ai_conversation (user_id, title, status, created_at, updated_at)"
                            + " VALUES (?, ?, 'ACTIVE', ?, ?)",
                    new String[] {"id"});
            ps.setInt(1, userId);
            ps.setString(2, shortTitle);
            ps.setObject(3, now);
            ps.setObject(4, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("新建会话后未返回主键");
        }
        return key.longValue();
    }

    public void insertConversationKb(long conversationId, long kbId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO ai_conversation_kb (conversation_id, kb_id, created_at)"
                        + " VALUES (?, ?, ?)",
                conversationId, kbId, now);
    }

    public long insertMessage(long conversationId, String role, String content,
            String status, String modelName, Integer latencyMs, String errorCode,
            LocalDateTime now) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO ai_message (conversation_id, role, content, status,"
                            + " model_name, latency_ms, error_code, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[] {"id"});
            ps.setLong(1, conversationId);
            ps.setString(2, role);
            ps.setString(3, content == null ? "" : content);
            ps.setString(4, status);
            ps.setString(5, modelName);
            if (latencyMs == null) {
                ps.setNull(6, Types.INTEGER);
            } else {
                ps.setInt(6, latencyMs);
            }
            ps.setString(7, errorCode);
            ps.setObject(8, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("新建消息后未返回主键");
        }
        return key.longValue();
    }

    public void updateMessage(long messageId, String content, String status,
            String modelName, Integer latencyMs, String errorCode, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE ai_message SET content = ?, status = ?, model_name = ?,"
                        + " latency_ms = ?, error_code = ? WHERE id = ?",
                content == null ? "" : content,
                status,
                modelName,
                latencyMs,
                errorCode,
                messageId);
        jdbcTemplate.update(
                "UPDATE ai_conversation c JOIN ai_message m ON m.conversation_id = c.id"
                        + " SET c.updated_at = ? WHERE m.id = ?",
                now, messageId);
    }

    public void insertCitation(long messageId, long chunkId, long docId,
            int rankNo, Double score, String quote, LocalDateTime now) {
        String shortQuote = quote == null ? null : quote.substring(0, Math.min(quote.length(), 1000));
        jdbcTemplate.update(
                "INSERT INTO ai_message_citation (message_id, chunk_id, doc_id, rank_no,"
                        + " score, quote, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                messageId, chunkId, docId, rankNo, score, shortQuote, now);
    }

    public ConversationRow findConversation(long conversationId, int userId) {
        List<ConversationRow> rows = jdbcTemplate.query(
                "SELECT id, user_id, title, status, created_at, updated_at"
                        + " FROM ai_conversation WHERE id = ? AND user_id = ?",
                CONVERSATION_MAPPER,
                conversationId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Long> findConversationKbIds(long conversationId) {
        return jdbcTemplate.queryForList(
                "SELECT kb_id FROM ai_conversation_kb WHERE conversation_id = ? ORDER BY kb_id",
                Long.class,
                conversationId);
    }

    public List<ConversationSummaryRow> listConversations(
            int userId, Long kbId, int offset, int limit) {
        String kbFilter = kbId == null ? "" : " AND EXISTS ("
                + "SELECT 1 FROM ai_conversation_kb f WHERE f.conversation_id = c.id AND f.kb_id = ?)";
        String sql = "SELECT c.id, c.title, c.created_at, c.updated_at,"
                + " (SELECT COUNT(*) FROM ai_message cm WHERE cm.conversation_id = c.id) AS message_count,"
                + " COALESCE((SELECT LEFT(cm2.content, 160) FROM ai_message cm2"
                + "   WHERE cm2.conversation_id = c.id ORDER BY cm2.id DESC LIMIT 1), '') AS last_message"
                + " FROM ai_conversation c WHERE c.user_id = ? AND c.status = 'ACTIVE'"
                + kbFilter + " ORDER BY c.updated_at DESC, c.id DESC LIMIT ? OFFSET ?";
        Object[] args = kbId == null
                ? new Object[] {userId, limit, offset}
                : new Object[] {userId, kbId, limit, offset};
        return jdbcTemplate.query(sql, SUMMARY_MAPPER, args);
    }

    public long countConversations(int userId, Long kbId) {
        String kbFilter = kbId == null ? "" : " AND EXISTS ("
                + "SELECT 1 FROM ai_conversation_kb f WHERE f.conversation_id = c.id AND f.kb_id = ?)";
        Long count = kbId == null
                ? jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_conversation c"
                                + " WHERE c.user_id = ? AND c.status = 'ACTIVE'",
                        Long.class, userId)
                : jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_conversation c"
                                + " WHERE c.user_id = ? AND c.status = 'ACTIVE'" + kbFilter,
                        Long.class, userId, kbId);
        return count == null ? 0 : count;
    }

    public boolean updateConversationTitle(
            long conversationId, int userId, String title, LocalDateTime now) {
        return jdbcTemplate.update(
                "UPDATE ai_conversation SET title = ?, updated_at = ?"
                        + " WHERE id = ? AND user_id = ? AND status = 'ACTIVE'",
                normalizeTitle(title), now, conversationId, userId) == 1;
    }

    public List<MessageRow> listMessages(long conversationId, int offset, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT * FROM ("
                        + " SELECT id, conversation_id, role, content, status, model_name,"
                        + " latency_ms, error_code, created_at FROM ai_message"
                        + " WHERE conversation_id = ? ORDER BY id DESC LIMIT ? OFFSET ?"
                        + ") latest ORDER BY id",
                MESSAGE_MAPPER,
                conversationId, limit, offset);
    }

    public List<MessageRow> listRecentHistory(long conversationId, int limit) {
        List<MessageRow> rows = jdbcTemplate.query(
                "SELECT id, conversation_id, role, content, status, model_name,"
                        + " latency_ms, error_code, created_at FROM ai_message"
                        + " WHERE conversation_id = ? AND role IN ('USER','ASSISTANT')"
                        + " AND status IN ('OK','CANCELLED') ORDER BY id DESC LIMIT ?",
                MESSAGE_MAPPER,
                conversationId, Math.max(1, limit));
        List<MessageRow> reversed = new ArrayList<>(rows);
        Collections.reverse(reversed);
        return reversed;
    }

    public long countMessages(long conversationId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_message WHERE conversation_id = ?",
                Long.class, conversationId);
        return count == null ? 0 : count;
    }

    public List<CitationRow> listCitations(long messageId) {
        return jdbcTemplate.query(
                "SELECT c.id, c.message_id, c.chunk_id, c.doc_id, d.name AS doc_name,"
                        + " d.kb_id, c.rank_no, ch.seq AS chunk_seq, c.score, c.quote,"
                        + " d.status AS doc_status"
                        + " FROM ai_message_citation c"
                        + " JOIN kb_document d ON d.id = c.doc_id"
                        + " JOIN kb_document_chunk ch ON ch.id = c.chunk_id"
                        + " WHERE c.message_id = ? ORDER BY c.rank_no, c.id",
                CITATION_MAPPER,
                messageId);
    }

    public CitationRow findCitationForUser(long citationId, int userId) {
        List<CitationRow> rows = jdbcTemplate.query(
                "SELECT c.id, c.message_id, c.chunk_id, c.doc_id, d.name AS doc_name,"
                        + " d.kb_id, c.rank_no, ch.seq AS chunk_seq, c.score, c.quote,"
                        + " d.status AS doc_status"
                        + " FROM ai_message_citation c"
                        + " JOIN ai_message m ON m.id = c.message_id"
                        + " JOIN ai_conversation conv ON conv.id = m.conversation_id"
                        + " JOIN kb_document d ON d.id = c.doc_id"
                        + " JOIN kb_document_chunk ch ON ch.id = c.chunk_id"
                        + " WHERE c.id = ? AND conv.user_id = ?",
                CITATION_MAPPER,
                citationId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void deleteConversation(long conversationId, int userId) {
        jdbcTemplate.update(
                "DELETE c FROM ai_message_citation c"
                        + " JOIN ai_message m ON m.id = c.message_id"
                        + " JOIN ai_conversation conv ON conv.id = m.conversation_id"
                        + " WHERE conv.id = ? AND conv.user_id = ?",
                conversationId, userId);
        jdbcTemplate.update(
                "DELETE m FROM ai_message m"
                        + " JOIN ai_conversation c ON c.id = m.conversation_id"
                        + " WHERE c.id = ? AND c.user_id = ?",
                conversationId, userId);
        jdbcTemplate.update(
                "DELETE ck FROM ai_conversation_kb ck"
                        + " JOIN ai_conversation c ON c.id = ck.conversation_id"
                        + " WHERE c.id = ? AND c.user_id = ?",
                conversationId, userId);
        jdbcTemplate.update(
                "DELETE FROM ai_conversation WHERE id = ? AND user_id = ?",
                conversationId, userId);
    }

    public String findDocName(long docId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT name FROM kb_document WHERE id = ?", String.class, docId);
        return rows.isEmpty() ? "" : rows.get(0);
    }

    private static String normalizeTitle(String title) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) {
            value = "新对话";
        }
        return value.substring(0, Math.min(value.length(), 255));
    }

    public record ConversationRow(long id, int userId, String title, String status,
            String createdAt, String updatedAt) {
    }

    public record ConversationSummaryRow(long id, String title, String lastMessage,
            int messageCount, String createdAt, String updatedAt) {
    }

    public record MessageRow(long id, long conversationId, String role, String content,
            String status, String modelName, Integer latencyMs, String errorCode,
            String createdAt) {
    }

    public record CitationRow(long id, long messageId, long chunkId, long docId,
            String docName, long kbId, int rankNo, int seq, Double score, String quote,
            String docStatus) {
    }
}
