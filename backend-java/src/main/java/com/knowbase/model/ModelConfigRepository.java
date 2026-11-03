package com.knowbase.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ModelConfigRepository {

    private final JdbcTemplate jdbcTemplate;

    public ModelConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ModelConfigRow> findAll() {
        return jdbcTemplate.query(
                "SELECT id, provider, model_type, model_name, base_url, api_key_cipher, params,"
                        + " enabled, last_test_at, last_test_ok, last_test_error, created_at, updated_at"
                        + " FROM ai_model_config ORDER BY model_type, provider, model_name",
                this::mapRow);
    }

    public Optional<ModelConfigRow> findById(long id) {
        List<ModelConfigRow> rows = jdbcTemplate.query(
                "SELECT id, provider, model_type, model_name, base_url, api_key_cipher, params,"
                        + " enabled, last_test_at, last_test_ok, last_test_error, created_at, updated_at"
                        + " FROM ai_model_config WHERE id = ?",
                this::mapRow, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<ModelConfigRow> findEnabled(ModelType type) {
        List<ModelConfigRow> rows = jdbcTemplate.query(
                "SELECT id, provider, model_type, model_name, base_url, api_key_cipher, params,"
                        + " enabled, last_test_at, last_test_ok, last_test_error, created_at, updated_at"
                        + " FROM ai_model_config WHERE model_type = ? AND enabled = 1"
                        + " ORDER BY updated_at DESC, id DESC LIMIT 1",
                this::mapRow, type.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long insert(String provider, ModelType type, String modelName, String baseUrl,
            String apiKeyCipher, String params, boolean enabled, LocalDateTime now) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO ai_model_config (provider, model_type, model_name, base_url,"
                            + " api_key_cipher, params, enabled, created_at, updated_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, provider);
            ps.setString(2, type.name());
            ps.setString(3, modelName);
            ps.setString(4, baseUrl);
            ps.setString(5, apiKeyCipher);
            ps.setString(6, params);
            ps.setInt(7, enabled ? 1 : 0);
            ps.setObject(8, now);
            ps.setObject(9, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("新建模型配置后未返回主键");
        }
        return key.longValue();
    }

    public int update(long id, String provider, ModelType type, String modelName, String baseUrl,
            String apiKeyCipher, String params, boolean enabled, LocalDateTime now) {
        return jdbcTemplate.update(
                "UPDATE ai_model_config SET provider = ?, model_type = ?, model_name = ?,"
                        + " base_url = ?, api_key_cipher = ?, params = ?, enabled = ?, updated_at = ?"
                        + " WHERE id = ?",
                provider, type.name(), modelName, baseUrl, apiKeyCipher, params,
                enabled ? 1 : 0, now, id);
    }

    public int delete(long id) {
        return jdbcTemplate.update("DELETE FROM ai_model_config WHERE id = ?", id);
    }

    public void updateTestResult(long id, boolean success, String error, LocalDateTime testedAt) {
        jdbcTemplate.update(
                "UPDATE ai_model_config SET last_test_at = ?, last_test_ok = ?,"
                        + " last_test_error = ?, updated_at = ? WHERE id = ?",
                testedAt, success ? 1 : 0, truncate(error, 512), testedAt, id);
    }

    private ModelConfigRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Integer lastTestOk = rs.getObject("last_test_ok") == null ? null : rs.getInt("last_test_ok");
        return new ModelConfigRow(
                rs.getLong("id"),
                rs.getString("provider"),
                rs.getString("model_type"),
                rs.getString("model_name"),
                rs.getString("base_url"),
                rs.getString("api_key_cipher"),
                rs.getString("params"),
                rs.getInt("enabled") == 1,
                rs.getString("last_test_at"),
                lastTestOk == null ? null : lastTestOk == 1,
                rs.getString("last_test_error"),
                rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record ModelConfigRow(long id, String provider, String modelType, String modelName,
            String baseUrl, String apiKeyCipher, String params, boolean enabled,
            String lastTestAt, Boolean lastTestOk, String lastTestError,
            String createdAt, String updatedAt) {

        @Override
        public String toString() {
            return "ModelConfigRow[id=" + id + ", provider=" + provider
                    + ", modelType=" + modelType + ", modelName=" + modelName + "]";
        }
    }
}
