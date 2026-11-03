package com.knowbase.audit;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OperationLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public OperationLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Integer operatorId, String action, String resourceType, String resourceId,
            boolean success, String detail, String ip, String traceId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO sys_operation_log (operator_id, action, resource_type, resource_id,"
                        + " result, detail, ip, trace_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                operatorId, truncate(action, 64), truncate(resourceType, 64),
                truncate(resourceId, 64), success ? "SUCCESS" : "FAILURE",
                truncate(detail, 2000), truncate(ip, 64), truncate(traceId, 64), now);
    }

    public List<OperationLogRow> list(String action, String result, int offset, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT l.id, l.operator_id, u.username, l.action, l.resource_type,"
                        + " l.resource_id, l.result, l.detail, l.ip, l.trace_id, l.created_at"
                        + " FROM sys_operation_log l LEFT JOIN sys_user u ON u.id = l.operator_id"
                        + " WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            sql.append(" AND l.action = ?");
            args.add(action.trim());
        }
        if (result != null && !result.isBlank()) {
            sql.append(" AND l.result = ?");
            args.add(result.trim());
        }
        sql.append(" ORDER BY l.created_at DESC, l.id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new OperationLogRow(
                        rs.getLong("id"),
                        rs.getObject("operator_id") == null ? null : rs.getInt("operator_id"),
                        rs.getString("username"),
                        rs.getString("action"),
                        rs.getString("resource_type"),
                        rs.getString("resource_id"),
                        rs.getString("result"),
                        rs.getString("detail"),
                        rs.getString("ip"),
                        rs.getString("trace_id"),
                        rs.getString("created_at")),
                args.toArray());
    }

    public long count(String action, String result) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM sys_operation_log WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            args.add(action.trim());
        }
        if (result != null && !result.isBlank()) {
            sql.append(" AND result = ?");
            args.add(result.trim());
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record OperationLogRow(long id, Integer operatorId, String username, String action,
            String resourceType, String resourceId, String result, String detail,
            String ip, String traceId, String createdAt) {
    }
}
