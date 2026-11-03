package com.knowbase.auth;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户 / 角色 / 登录日志的数据访问。
 *
 * <p>只暴露鉴权需要的字段：密码哈希仅在登录校验时读取，
 * 不提供「按条件查用户列表」这类会被误用成信息泄露入口的方法。
 */
@Repository
public class UserRepository {

    private static final String USER_COLUMNS =
            "id, username, password_hash, nickname, status, token_version";

    private static final RowMapper<UserAccount> USER_MAPPER = (rs, rowNum) -> new UserAccount(
            rs.getInt("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("nickname"),
            rs.getString("status"),
            rs.getInt("token_version"));

    /**
     * 每个请求都要复核用户状态与 token_version，因此单独查一次而不是复用登录时的对象。
     * 这里只取鉴权必需列，不读 password_hash。
     */
    private static final RowMapper<UserState> STATE_MAPPER = (rs, rowNum) -> new UserState(
            rs.getInt("id"),
            rs.getString("username"),
            rs.getString("nickname"),
            rs.getString("status"),
            rs.getInt("token_version"));

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按用户名取账户（含密码哈希）。用户名有唯一索引，最多一行。 */
    public Optional<UserAccount> findByUsername(String username) {
        List<UserAccount> rows = jdbcTemplate.query(
                "SELECT " + USER_COLUMNS + " FROM sys_user WHERE username = ?", USER_MAPPER, username);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 按主键取鉴权状态。
     *
     * <p>{@code queryForObject} 在没有命中时抛 {@link EmptyResultDataAccessException}——
     * 用户被物理删除（正常情况下不会发生，但 DDL 允许）时必须转成空 Optional，而不是冒到通用 500。
     */
    public Optional<UserState> findStateById(int userId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT id, username, nickname, status, token_version FROM sys_user WHERE id = ?",
                    STATE_MAPPER, userId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** 用户的有效角色编码。被禁用的角色不算权限，因此过滤 {@code sys_role.status}。 */
    public List<String> findRoleCodes(int userId) {
        return jdbcTemplate.queryForList(
                "SELECT r.code FROM sys_user_role ur "
                        + "JOIN sys_role r ON r.id = ur.role_id "
                        + "WHERE ur.user_id = ? AND r.status = 'ACTIVE' "
                        + "ORDER BY r.code",
                String.class,
                userId);
    }

    /** 全部用户（包含已禁用），供管理员用户管理使用。 */
    public List<ManagedUser> listManagedUsers() {
        return jdbcTemplate.query(
                "SELECT id, username, nickname, email, status, token_version,"
                        + " last_login_at, created_at FROM sys_user ORDER BY created_at DESC",
                (rs, rowNum) -> new ManagedUser(
                        rs.getInt("id"), rs.getString("username"), rs.getString("nickname"),
                        rs.getString("email"), rs.getString("status"), rs.getInt("token_version"),
                        rs.getString("last_login_at"), rs.getString("created_at")));
    }

    public Optional<ManagedUser> findManagedUser(int userId) {
        List<ManagedUser> rows = jdbcTemplate.query(
                "SELECT id, username, nickname, email, status, token_version,"
                        + " last_login_at, created_at FROM sys_user WHERE id = ?",
                (rs, rowNum) -> new ManagedUser(
                        rs.getInt("id"), rs.getString("username"), rs.getString("nickname"),
                        rs.getString("email"), rs.getString("status"), rs.getInt("token_version"),
                        rs.getString("last_login_at"), rs.getString("created_at")),
                userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long insertUser(String username, String passwordHash, String nickname, String email,
            String status, LocalDateTime now) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO sys_user (username, password_hash, nickname, email, status,"
                            + " token_version, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 0, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, username);
            ps.setString(2, passwordHash);
            ps.setString(3, nickname);
            ps.setString(4, email);
            ps.setString(5, status);
            ps.setObject(6, now);
            ps.setObject(7, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("新建用户后未返回主键");
        }
        return key.longValue();
    }

    public void updateProfile(int userId, String nickname, String email, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE sys_user SET nickname = ?, email = ?, updated_at = ? WHERE id = ?",
                nickname, email, now, userId);
    }

    public void updateStatusAndInvalidateTokens(int userId, String status, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE sys_user SET status = ?, token_version = token_version + 1, updated_at = ?"
                        + " WHERE id = ?",
                status, now, userId);
    }

    public void updatePasswordAndInvalidateTokens(int userId, String passwordHash, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE sys_user SET password_hash = ?, token_version = token_version + 1,"
                        + " updated_at = ? WHERE id = ?",
                passwordHash, now, userId);
    }

    public void invalidateTokens(int userId, LocalDateTime now) {
        jdbcTemplate.update(
                "UPDATE sys_user SET token_version = token_version + 1, updated_at = ? WHERE id = ?",
                now, userId);
    }

    /**
     * 登录成功时轮换 token_version，只保留本次登录签发的会话有效。
     * 行锁保证并发登录也会得到不同的版本号，后登录设备使前序设备失效。
     */
    @Transactional
    public int rotateTokenVersionForLogin(int userId, LocalDateTime now) {
        List<Integer> versions = jdbcTemplate.queryForList(
                "SELECT token_version FROM sys_user WHERE id = ? FOR UPDATE",
                Integer.class,
                userId);
        if (versions.isEmpty()) {
            throw new IllegalStateException("登录用户不存在");
        }
        int nextVersion = Math.addExact(versions.get(0), 1);
        jdbcTemplate.update(
                "UPDATE sys_user SET token_version = ?, updated_at = ? WHERE id = ?",
                nextVersion, now, userId);
        return nextVersion;
    }

    public Optional<String> findPasswordHash(int userId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT password_hash FROM sys_user WHERE id = ?", String.class, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 根据稳定角色编码取角色 id；不存在或被禁用的角色不会返回。 */
    public List<Integer> findActiveRoleIds(Set<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(roleCodes.size(), "?"));
        return jdbcTemplate.queryForList(
                "SELECT id FROM sys_role WHERE code IN (" + placeholders + ") AND status = 'ACTIVE'",
                Integer.class, roleCodes.toArray());
    }

    public void replaceRoles(int userId, List<Integer> roleIds, Integer grantedBy, LocalDateTime now) {
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
        for (Integer roleId : roleIds) {
            jdbcTemplate.update(
                    "INSERT INTO sys_user_role (user_id, role_id, granted_by, created_at)"
                            + " VALUES (?, ?, ?, ?)",
                    userId, roleId, grantedBy, now);
        }
    }

    public long countActiveAdminsExcluding(int excludedUserId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT u.id) FROM sys_user u"
                        + " JOIN sys_user_role ur ON ur.user_id = u.id"
                        + " JOIN sys_role r ON r.id = ur.role_id"
                        + " WHERE u.status = 'ACTIVE' AND r.code = 'SYS_ADMIN' AND u.id <> ?",
                Long.class, excludedUserId);
        return count == null ? 0 : count;
    }

    public void touchLastLogin(int userId, LocalDateTime loginAt) {
        jdbcTemplate.update("UPDATE sys_user SET last_login_at = ?, updated_at = ? WHERE id = ?",
                loginAt, loginAt, userId);
    }

    /**
     * 写登录日志。
     *
     * <p>{@code user_id} 在「用户名不存在」时为 null——DDL 允许，且能留下爆破痕迹而不暴露账号是否存在。
     *
     * @param success 对应 {@code success} 列的 1 / 0
     */
    public void insertLoginLog(Integer userId, String username, String ip, String userAgent,
                               boolean success, String failReason, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO sys_user_login_log (user_id, username, ip, user_agent, success, fail_reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, trimTo(username, 64), trimTo(ip, 64), trimTo(userAgent, 512),
                success ? 1 : 0, trimTo(failReason, 128), createdAt);
    }

    /** 列宽保护：客户端可以塞任意长的 User-Agent，不截断会直接触发 SQL 错误。 */
    private static String trimTo(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * 登录校验用的账户快照（含密码哈希）。
     *
     * <p>哈希字符串只在本对象与 {@code BCryptPasswordEncoder.matches} 之间传递，
     * 任何日志、toString、响应体都不允许包含它。
     */
    public record UserAccount(int id, String username, String passwordHash, String nickname,
                              String status, int tokenVersion) {
        @Override
        public String toString() {
            return "UserAccount[id=" + id + ", username=" + username + ", status=" + status
                    + ", tokenVersion=" + tokenVersion + "]";
        }
    }

    /** 每请求复核用的最小快照，不含密码哈希。 */
    public record UserState(int id, String username, String nickname, String status, int tokenVersion) {
    }

    /** 管理端用户快照。password_hash 永远不会进入这个对象。 */
    public record ManagedUser(int id, String username, String nickname, String email,
            String status, int tokenVersion, String lastLoginAt, String createdAt) {
    }
}
