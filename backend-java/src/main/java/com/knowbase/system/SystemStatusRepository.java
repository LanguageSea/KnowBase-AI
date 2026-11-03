package com.knowbase.system;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SystemStatusRepository {

    private final JdbcTemplate jdbcTemplate;

    public SystemStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long countTasks(String status) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_ingestion_task WHERE status = ?", Long.class, status);
        return count == null ? 0 : count;
    }

    public long countFailedDocuments() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document WHERE status IN ('FAILED','DELETE_FAILED')",
                Long.class);
        return count == null ? 0 : count;
    }

    public List<ErrorCount> topTaskErrors(LocalDateTime since, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return jdbcTemplate.query(
                "SELECT COALESCE(error_code, 'UNKNOWN') AS error_code, COUNT(*) AS error_count"
                        + " FROM kb_ingestion_task"
                        + " WHERE created_at >= ? AND status IN ('FAILED','DEAD')"
                        + " GROUP BY COALESCE(error_code, 'UNKNOWN')"
                        + " ORDER BY error_count DESC LIMIT " + safeLimit,
                (rs, rowNum) -> new ErrorCount(rs.getString("error_code"), rs.getLong("error_count")),
                since);
    }

    public record ErrorCount(String code, long count) {
    }
}
