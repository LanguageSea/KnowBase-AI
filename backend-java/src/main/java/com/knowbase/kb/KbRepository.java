package com.knowbase.kb;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 知识库数据访问（docs/API_SPEC.md §2，DDL 见 V1__init.sql §6）。
 *
 * <p>Stage 1 只认 owner_id + ACTIVE，无成员表查询。重名冲突靠 uk_kb_owner_name，
 * 捕获 DuplicateKeyException 转 409，不做“先查后插”（并发下必有竞态）。
 */
@Repository
public class KbRepository {

    private static final RowMapper<KbRow> KB_MAPPER = (rs, rowNum) -> new KbRow(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("created_at") != null ? rs.getString("created_at") : null);

    private final JdbcTemplate jdbcTemplate;

    public KbRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 当前用户 ACTIVE 库列表（含各库 ACTIVE 文档数）。 */
    public List<KbWithCount> listActiveByOwner(int ownerId) {
        return jdbcTemplate.query(
                "SELECT k.id, k.name, k.description, k.created_at,"
                        + " (SELECT COUNT(*) FROM kb_document d"
                        + " WHERE d.kb_id = k.id AND d.status = 'ACTIVE') AS doc_count"
                        + " FROM kb_knowledge_base k"
                        + " WHERE k.owner_id = ? AND k.status = 'ACTIVE'"
                        + " ORDER BY k.created_at DESC",
                (rs, rowNum) -> new KbWithCount(
                        rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                        rs.getLong("doc_count"), rs.getString("created_at")),
                ownerId);
    }

    /** 自有或已授权的 ACTIVE 库，UNION 语义去重。 */
    public List<KbWithCount> listReadable(int userId) {
        return jdbcTemplate.query(
                "SELECT k.id, k.name, k.description, k.created_at,"
                        + " (SELECT COUNT(*) FROM kb_document d"
                        + " WHERE d.kb_id = k.id AND d.status = 'ACTIVE') AS doc_count"
                        + " FROM kb_knowledge_base k"
                        + " WHERE k.status = 'ACTIVE' AND (k.owner_id = ? OR EXISTS ("
                        + "   SELECT 1 FROM kb_member m WHERE m.kb_id = k.id AND m.user_id = ?))"
                        + " ORDER BY k.created_at DESC",
                (rs, rowNum) -> new KbWithCount(
                        rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                        rs.getLong("doc_count"), rs.getString("created_at")),
                userId, userId);
    }

    public List<Long> findReadableIds(int userId) {
        return jdbcTemplate.queryForList(
                "SELECT k.id FROM kb_knowledge_base k"
                        + " WHERE k.status = 'ACTIVE' AND (k.owner_id = ? OR EXISTS ("
                        + "   SELECT 1 FROM kb_member m WHERE m.kb_id = k.id AND m.user_id = ?))"
                        + " ORDER BY k.id",
                Long.class, userId, userId);
    }

    /** 一次查询返回库状态、owner 与当前用户成员角色。 */
    public Optional<KbAccess> findAccess(long kbId, int userId) {
        List<KbAccess> rows = jdbcTemplate.query(
                "SELECT k.id, k.owner_id, k.status, m.role AS member_role"
                        + " FROM kb_knowledge_base k"
                        + " LEFT JOIN kb_member m ON m.kb_id = k.id AND m.user_id = ?"
                        + " WHERE k.id = ?",
                (rs, rowNum) -> new KbAccess(
                        rs.getLong("id"), rs.getInt("owner_id"), rs.getString("status"),
                        rs.getString("member_role")),
                userId, kbId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 按 id 取库；不存在返回空（Service 统一转 404，不暴露是否存在）。 */
    public Optional<KbRow> findById(long kbId) {
        List<KbRow> rows = jdbcTemplate.query(
                "SELECT id, name, description, created_at FROM kb_knowledge_base WHERE id = ?",
                KB_MAPPER, kbId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 取库 owner；不存在返回 null（Service 统一转 404）。 */
    public Long findOwnerId(long kbId) {
        List<Long> rows = jdbcTemplate.queryForList(
                "SELECT owner_id FROM kb_knowledge_base WHERE id = ?", Long.class, kbId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 取库状态；不存在返回 null（Service 统一转 404）。 */
    public String findStatus(long kbId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT status FROM kb_knowledge_base WHERE id = ?", String.class, kbId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 指定库 ACTIVE 文档数。 */
    public long countActiveDocs(long kbId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document WHERE kb_id = ? AND status = 'ACTIVE'",
                Long.class, kbId);
        return count == null ? 0 : count;
    }

    /**
     * 建库。
     *
     * @return 新库 id
     * @throws DuplicateKeyException 命中 uk_kb_owner_name（同 owner 重名）
     */
    public long insert(int ownerId, String name, String description, LocalDateTime now) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO kb_knowledge_base (name, description, owner_id, status, created_at, updated_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                    new String[] {"id"});
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setInt(3, ownerId);
            ps.setObject(4, now);
            ps.setObject(5, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("建库后未返回主键");
        }
        return key.longValue();
    }

    public void insertOwnerMember(long kbId, int ownerId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO kb_member (kb_id, user_id, role, invited_by, created_at, updated_at)"
                        + " VALUES (?, ?, 'MANAGER', ?, ?, ?)",
                kbId, ownerId, ownerId, now, now);
    }

    public List<MemberRow> listMembers(long kbId) {
        return jdbcTemplate.query(
                "SELECT m.user_id, u.username, u.nickname, m.role, m.created_at"
                        + " FROM kb_member m JOIN sys_user u ON u.id = m.user_id"
                        + " WHERE m.kb_id = ? ORDER BY m.created_at",
                (rs, rowNum) -> new MemberRow(
                        rs.getInt("user_id"), rs.getString("username"), rs.getString("nickname"),
                        rs.getString("role"), rs.getString("created_at")),
                kbId);
    }

    public Optional<MemberRow> findMember(long kbId, int userId) {
        List<MemberRow> rows = jdbcTemplate.query(
                "SELECT m.user_id, u.username, u.nickname, m.role, m.created_at"
                        + " FROM kb_member m JOIN sys_user u ON u.id = m.user_id"
                        + " WHERE m.kb_id = ? AND m.user_id = ?",
                (rs, rowNum) -> new MemberRow(
                        rs.getInt("user_id"), rs.getString("username"), rs.getString("nickname"),
                        rs.getString("role"), rs.getString("created_at")),
                kbId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void upsertMember(long kbId, int userId, String role, int operatorId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO kb_member (kb_id, user_id, role, invited_by, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE role = VALUES(role), invited_by = VALUES(invited_by),"
                        + " updated_at = VALUES(updated_at)",
                kbId, userId, role, operatorId, now, now);
    }

    public void deleteMember(long kbId, int userId) {
        jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ? AND user_id = ?", kbId, userId);
    }

    public boolean activeUserExists(int userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id = ? AND status = 'ACTIVE'",
                Long.class, userId);
        return count != null && count > 0;
    }

    public List<UserCandidate> searchActiveUsers(String keyword) {
        String normalized = keyword == null ? "" : keyword.trim();
        String like = "%" + normalized + "%";
        return jdbcTemplate.query(
                "SELECT id, username, nickname FROM sys_user"
                        + " WHERE status = 'ACTIVE' AND (username LIKE ? OR nickname LIKE ?)"
                        + " ORDER BY username LIMIT 10",
                (rs, rowNum) -> new UserCandidate(
                        rs.getInt("id"), rs.getString("username"), rs.getString("nickname")),
                like, like);
    }

    public void insertGrantAudit(long kbId, int targetUserId, String action,
            String oldRole, String newRole, int operatorId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO kb_audit_grant (kb_id, target_user_id, action, old_role, new_role,"
                        + " operator_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                kbId, targetUserId, action, oldRole, newRole, operatorId, now);
    }

    /** 库行（不含 docCount，需要时另查）。 */
    public record KbRow(long id, String name, String description, String createdAt) {
    }

    /** 库行 + ACTIVE 文档数。 */
    public record KbWithCount(long id, String name, String description, long docCount, String createdAt) {
    }

    public record KbAccess(long kbId, int ownerId, String status, String memberRole) {
    }

    public record MemberRow(int userId, String username, String nickname, String role,
            String createdAt) {
    }

    public record UserCandidate(int userId, String username, String nickname) {
    }
}
