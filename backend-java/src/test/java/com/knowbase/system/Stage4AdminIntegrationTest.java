package com.knowbase.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Opt-in Stage 4 acceptance test against the real dev MySQL database.
 *
 * <p>Preconditions: the dev profile has loaded the seeded admin/viewer accounts.
 * Redis is mocked only for deterministic login throttling; model operations do
 * not call an upstream AI provider.
 */
@EnabledIfEnvironmentVariable(named = "KNOWBASE_STAGE4_IT", matches = "(?i)true")
@SpringBootTest
class Stage4AdminIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_PASSWORD = "Admin@123456";
    private static final String VIEWER_PASSWORD = "Test@123456";
    private static final String API_KEY = "sk-stage4-it-1234567890abcd";
    private static final String MASKED_API_KEY = "sk-****abcd";
    private static final String MODEL_BASE_URL = "https://8.8.8.8/v1";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private List<Filter> filters;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUpMockMvc() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AtomicLong counter = new AtomicLong();
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment(anyString())).thenAnswer(ignored -> counter.incrementAndGet());
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.delete(anyString())).thenReturn(true);
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(filters.toArray(Filter[]::new))
                .build();
    }

    @Test
    void regularUserIsRejectedByClassLevelAdminPermission() throws Exception {
        String token = login("viewer", VIEWER_PASSWORD);

        mockMvc.perform(get("/admin/models")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/system/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/admin/audit-logs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminModelCrudStoresCiphertextMasksResponsesAndWritesAudit() throws Exception {
        String token = login("admin", ADMIN_PASSWORD);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long modelId = 0;
        try {
            Map<String, Object> createRequest = new LinkedHashMap<>();
            createRequest.put("provider", "stage4-it-" + suffix);
            createRequest.put("modelType", "CHAT");
            createRequest.put("modelName", "stage4-model-" + suffix);
            createRequest.put("baseUrl", MODEL_BASE_URL);
            createRequest.put("apiKey", API_KEY);
            createRequest.put("params", Map.of("temperature", 0));
            createRequest.put("enabled", true);

            JsonNode created = json(mockMvc.perform(post("/admin/models")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsBytes(createRequest)))
                    .andExpect(status().isOk())
                    .andReturn()).path("data");
            modelId = created.path("id").asLong();
            assertThat(modelId).isPositive();
            assertThat(created.path("maskedApiKey").asText()).isEqualTo(MASKED_API_KEY);
            assertThat(created.path("hasApiKey").asBoolean()).isTrue();
            assertThat(created.path("keyStatus").asText()).isEqualTo("OK");

            String firstCiphertext = apiKeyCipher(modelId);
            assertThat(firstCiphertext).startsWith("v1:").doesNotContain(API_KEY);

            Map<String, Object> updateRequest = new LinkedHashMap<>();
            updateRequest.put("provider", "stage4-it-" + suffix);
            updateRequest.put("modelType", "CHAT");
            updateRequest.put("modelName", "stage4-model-" + suffix);
            updateRequest.put("baseUrl", MODEL_BASE_URL);
            updateRequest.put("clearApiKey", false);
            updateRequest.put("params", Map.of("temperature", 0));
            updateRequest.put("enabled", true);

            JsonNode updated = json(mockMvc.perform(put("/admin/models/{modelId}", modelId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsBytes(updateRequest)))
                    .andExpect(status().isOk())
                    .andReturn()).path("data");
            assertThat(updated.path("maskedApiKey").asText()).isEqualTo(MASKED_API_KEY);
            assertThat(updated.path("hasApiKey").asBoolean()).isTrue();

            String retainedCiphertext = apiKeyCipher(modelId);
            assertThat(retainedCiphertext).startsWith("v1:").isNotEqualTo(firstCiphertext);

            JsonNode listed = json(mockMvc.perform(get("/admin/models")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn()).path("data");
            long listedModelId = modelId;
            assertThat(listed.path("items"))
                    .anySatisfy(item -> assertThat(item.path("id").asLong()).isEqualTo(listedModelId));

            Long auditRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM sys_operation_log"
                            + " WHERE action = 'MODEL_CREATE' AND resource_type = 'MODEL'"
                            + " AND resource_id = ? AND result = 'SUCCESS'",
                    Long.class,
                    String.valueOf(modelId));
            assertThat(auditRows).isEqualTo(1);

            updateRequest.put("clearApiKey", true);
            JsonNode cleared = json(mockMvc.perform(put("/admin/models/{modelId}", modelId)
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsBytes(updateRequest)))
                    .andExpect(status().isOk())
                    .andReturn()).path("data");
            assertThat(cleared.path("maskedApiKey").isNull()).isTrue();
            assertThat(cleared.path("hasApiKey").asBoolean()).isFalse();
            assertThat(cleared.path("keyStatus").asText()).isEqualTo("EMPTY");
            assertThat(apiKeyCipher(modelId)).isNull();

            mockMvc.perform(delete("/admin/models/{modelId}", modelId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            Long remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM ai_model_config WHERE id = ?", Long.class, modelId);
            assertThat(remaining).isZero();
        } finally {
            cleanupModel(modelId);
        }
    }

    @Test
    void personalWorkspaceAndSystemStatusMatchDatabaseCounts() throws Exception {
        String viewerToken = login("viewer", VIEWER_PASSWORD);
        Integer viewerId = jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = 'viewer'", Integer.class);

        JsonNode summary = json(mockMvc.perform(get("/workspace/summary")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(summary.path("scope").asText()).isEqualTo("PERSONAL");
        assertThat(summary.path("accessibleKbCount").asLong())
                .isEqualTo(readableKbCount(viewerId));
        assertThat(summary.path("activeDocumentCount").asLong())
                .isEqualTo(countDocuments(viewerId, "ACTIVE"));
        assertThat(summary.path("processingDocumentCount").asLong())
                .isEqualTo(countProcessingDocuments(viewerId));
        assertThat(summary.path("conversationCount").asLong())
                .isEqualTo(countConversations(viewerId));
        assertThat(summary.path("questionCount").asLong())
                .isEqualTo(countQuestions(viewerId));
        assertThat(summary.path("dailyQuestions")).hasSize(7);
        assertThat(summary.path("latestDocuments").size()).isLessThanOrEqualTo(5);

        String adminToken = login("admin", ADMIN_PASSWORD);
        JsonNode system = json(mockMvc.perform(get("/admin/system/status")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        List<String> componentNames = new ArrayList<>();
        system.path("components").forEach(component ->
                componentNames.add(component.path("name").asText()));
        assertThat(componentNames).containsExactly("mysql", "redis", "qdrant", "python");

        JsonNode backlog = system.path("backlog");
        assertThat(backlog.path("queued").asLong()).isEqualTo(countTasks("QUEUED"));
        assertThat(backlog.path("running").asLong()).isEqualTo(countTasks("RUNNING"));
        assertThat(backlog.path("failed").asLong()).isEqualTo(countTasks("FAILED"));
        assertThat(backlog.path("dead").asLong()).isEqualTo(countTasks("DEAD"));
        assertThat(backlog.path("failedDocuments").asLong()).isEqualTo(countFailedDocuments());
        assertThat(system.path("taskErrorTop5").isArray()).isTrue();
        assertThat(system.path("aiErrorTop5").isArray()).isTrue();
    }

    private String login(String username, String password) throws Exception {
        JsonNode body = json(mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn());
        String token = body.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String apiKeyCipher(long modelId) {
        return jdbcTemplate.queryForObject(
                "SELECT api_key_cipher FROM ai_model_config WHERE id = ?",
                String.class,
                modelId);
    }

    private long readableKbCount(int userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_knowledge_base k"
                        + " WHERE k.status = 'ACTIVE' AND (k.owner_id = ? OR EXISTS ("
                        + "   SELECT 1 FROM kb_member m WHERE m.kb_id = k.id AND m.user_id = ?))",
                Long.class, userId, userId);
        return count == null ? 0 : count;
    }

    private long countDocuments(int userId, String documentStatus) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document d"
                        + " JOIN kb_knowledge_base k ON k.id = d.kb_id"
                        + " WHERE d.status = ? AND k.status = 'ACTIVE'"
                        + " AND (k.owner_id = ? OR EXISTS ("
                        + "   SELECT 1 FROM kb_member m WHERE m.kb_id = k.id AND m.user_id = ?))",
                Long.class, documentStatus, userId, userId);
        return count == null ? 0 : count;
    }

    private long countProcessingDocuments(int userId) {
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

    private long countConversations(int userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_conversation WHERE user_id = ? AND status = 'ACTIVE'",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    private long countQuestions(int userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_message msg"
                        + " JOIN ai_conversation c ON c.id = msg.conversation_id"
                        + " WHERE c.user_id = ? AND msg.role = 'USER'",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    private long countTasks(String taskStatus) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_ingestion_task WHERE status = ?",
                Long.class, taskStatus);
        return count == null ? 0 : count;
    }

    private long countFailedDocuments() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document WHERE status IN ('FAILED','DELETE_FAILED')",
                Long.class);
        return count == null ? 0 : count;
    }

    private void cleanupModel(long modelId) {
        if (modelId <= 0) {
            return;
        }
        jdbcTemplate.update("DELETE FROM ai_call_log WHERE model_config_id = ?", modelId);
        jdbcTemplate.update(
                "DELETE FROM sys_operation_log WHERE resource_type = 'MODEL' AND resource_id = ?",
                String.valueOf(modelId));
        jdbcTemplate.update("DELETE FROM ai_model_config WHERE id = ?", modelId);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
}
