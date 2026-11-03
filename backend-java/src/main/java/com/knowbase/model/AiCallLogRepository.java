package com.knowbase.model;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AiCallLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AiCallLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(Long modelConfigId, String scene, boolean success, int latencyMs,
            Integer promptTokens, Integer completionTokens, String errorType, String errorMsg,
            String traceId, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO ai_call_log (model_config_id, scene, success, latency_ms,"
                        + " prompt_tokens, completion_tokens, error_type, error_msg, trace_id, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                modelConfigId, truncate(scene, 32), success ? 1 : 0, latencyMs,
                promptTokens, completionTokens, truncate(errorType, 32),
                truncate(errorMsg, 512), truncate(traceId, 64), now);
    }

    public long countSince(LocalDateTime since) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_call_log WHERE created_at >= ?", Long.class, since);
        return count == null ? 0 : count;
    }

    public long countSuccessSince(LocalDateTime since, boolean success) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_call_log WHERE created_at >= ? AND success = ?",
                Long.class, since, success ? 1 : 0);
        return count == null ? 0 : count;
    }

    public List<ModelAggregateRow> aggregateByModel(LocalDateTime since) {
        return jdbcTemplate.query(
                "SELECT c.model_config_id, m.provider, m.model_type, m.model_name,"
                        + " COUNT(*) AS call_count, SUM(c.success) AS success_count,"
                        + " AVG(c.latency_ms) AS avg_latency_ms"
                        + " FROM ai_call_log c LEFT JOIN ai_model_config m ON m.id = c.model_config_id"
                        + " WHERE c.created_at >= ?"
                        + " GROUP BY c.model_config_id, m.provider, m.model_type, m.model_name"
                        + " ORDER BY call_count DESC, c.model_config_id",
                (rs, rowNum) -> new ModelAggregateRow(
                        rs.getObject("model_config_id") == null ? null : rs.getLong("model_config_id"),
                        rs.getString("provider"),
                        rs.getString("model_type"),
                        rs.getString("model_name"),
                        rs.getLong("call_count"),
                        rs.getLong("success_count"),
                        rs.getDouble("avg_latency_ms")),
                since);
    }

    public List<DailyAggregateRow> aggregateByDay(LocalDateTime since) {
        return jdbcTemplate.query(
                "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day,"
                        + " COUNT(*) AS call_count, SUM(success) AS success_count,"
                        + " AVG(latency_ms) AS avg_latency_ms"
                        + " FROM ai_call_log WHERE created_at >= ? GROUP BY day ORDER BY day",
                (rs, rowNum) -> new DailyAggregateRow(
                        rs.getString("day"), rs.getLong("call_count"),
                        rs.getLong("success_count"), rs.getDouble("avg_latency_ms")),
                since);
    }

    public List<ErrorAggregateRow> aggregateErrors(LocalDateTime since, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 20));
        return jdbcTemplate.query(
                "SELECT COALESCE(error_type, 'UNKNOWN') AS error_type, COUNT(*) AS error_count"
                        + " FROM ai_call_log WHERE created_at >= ? AND success = 0"
                        + " GROUP BY COALESCE(error_type, 'UNKNOWN')"
                        + " ORDER BY error_count DESC LIMIT " + safeLimit,
                (rs, rowNum) -> new ErrorAggregateRow(
                        rs.getString("error_type"), rs.getLong("error_count")),
                since);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record ModelAggregateRow(Long modelConfigId, String provider, String modelType,
            String modelName, long callCount, long successCount, double avgLatencyMs) {
    }

    public record DailyAggregateRow(String day, long callCount, long successCount,
            double avgLatencyMs) {
    }

    public record ErrorAggregateRow(String errorType, long count) {
    }
}
