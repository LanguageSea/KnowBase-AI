package com.knowbase.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowbase.health.ComponentHealth;
import com.knowbase.health.HealthReport;
import com.knowbase.health.HealthService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * T5.5: every protected controller method is exercised with the three system
 * roles. The assertions focus on the authorization gate: admin-only endpoints
 * return 403 for non-admins, resource writes return 403 for VIEWER, and all
 * authenticated role combinations otherwise reach the domain layer.
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "KNOWBASE_STAGE5_IT", matches = "(?i)true")
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "spring.flyway.ignore-migration-patterns=*:missing",
        "knowbase.tasks.poll-interval-ms=600000",
        "knowbase.tasks.stale-scan-interval-ms=600000",
        "knowbase.tasks.delete-compensation-interval-ms=600000"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissionMatrixIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_USERNAME = "stage5_admin";
    private static final String KB_ADMIN_USERNAME = "stage5_kbadmin";
    private static final String VIEWER_USERNAME = "stage5_viewer";
    private static final String TEST_PASSWORD = "Test@123456";
    private static final String TEST_HASH =
            "$2a$10$Lg4xxK7lHsVFSVDV16Orce.h7RNI8O/mGbIVJX3QjcF6A9p7hbOh2";
    private static final Path UPLOAD_DIR = Path.of(
            System.getProperty("java.io.tmpdir"), "knowbase-permission-" + UUID.randomUUID());

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault(
                "KNOWBASE_STAGE5_MYSQL_URL",
                "jdbc:mysql://127.0.0.1:3306/knowbaseai"
                        + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                        + "&sslMode=DISABLED&allowPublicKeyRetrieval=true"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("KNOWBASE_STAGE5_MYSQL_USER", "root"));
        registry.add("spring.datasource.password",
                () -> System.getenv().getOrDefault("KNOWBASE_STAGE5_MYSQL_PASSWORD", "root"));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("knowbase.storage.upload-dir", UPLOAD_DIR::toString);
        registry.add("knowbase.security.master-key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("knowbase.security.internal-token",
                () -> "S5-permission-matrix-key-abcdefghijklmnopqrstuvwxyz");
        registry.add("knowbase.python.base-url", () -> "http://127.0.0.1:1");
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private List<Filter> filters;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private HealthService healthService;

    private MockMvc mockMvc;
    private long kbId;
    private long docId;
    private int adminId;
    private int kbAdminId;
    private int viewerId;
    private String kbName;
    private String createdKbName;

    @BeforeAll
    void seedMatrixFixture() throws Exception {
        Files.createDirectories(UPLOAD_DIR);

        upsertUser(ADMIN_USERNAME, "矩阵系统管理员");
        upsertUser(KB_ADMIN_USERNAME, "矩阵知识库管理员");
        upsertUser(VIEWER_USERNAME, "矩阵普通用户");
        adminId = userId(ADMIN_USERNAME);
        kbAdminId = userId(KB_ADMIN_USERNAME);
        viewerId = userId(VIEWER_USERNAME);
        assignRole(adminId, "SYS_ADMIN");
        assignRole(adminId, "USER");
        assignRole(kbAdminId, "KB_ADMIN");
        assignRole(kbAdminId, "USER");
        assignRole(viewerId, "USER");

        LocalDateTime now = LocalDateTime.now();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        kbName = "permission-matrix-" + suffix;
        createdKbName = "permission-created-" + suffix;
        jdbcTemplate.update("""
                INSERT INTO kb_knowledge_base
                    (name, description, owner_id, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """, kbName, "Stage 5 permission matrix", adminId, now, now);
        kbId = jdbcTemplate.queryForObject(
                "SELECT id FROM kb_knowledge_base WHERE name = ?", Long.class, kbName);
        jdbcTemplate.update("""
                INSERT INTO kb_member (kb_id, user_id, role, invited_by, created_at, updated_at)
                VALUES (?, ?, 'MANAGER', ?, ?, ?)
                """, kbId, kbAdminId, adminId, now, now);
        jdbcTemplate.update("""
                INSERT INTO kb_member (kb_id, user_id, role, invited_by, created_at, updated_at)
                VALUES (?, ?, 'VIEWER', ?, ?, ?)
                """, kbId, viewerId, adminId, now, now);

        String storageKey = "matrix/source.txt";
        Path source = UPLOAD_DIR.resolve(storageKey);
        Files.createDirectories(source.getParent());
        Files.writeString(source, "Stage 5 permission matrix source", StandardCharsets.UTF_8);
        String sha = "a".repeat(64);
        jdbcTemplate.update("""
                INSERT INTO kb_document
                    (kb_id, name, ext, mime_type, size_bytes, sha256, dedup_key, storage_key,
                     status, chunk_count, uploaded_by, indexed_at, created_at, updated_at)
                VALUES (?, 'source.txt', 'txt', 'text/plain', 32, ?, ?, ?,
                        'ACTIVE', 1, ?, ?, ?, ?)
                """, kbId, sha, sha, storageKey, adminId, now, now, now);
        docId = jdbcTemplate.queryForObject(
                "SELECT id FROM kb_document WHERE kb_id = ? AND name = 'source.txt'",
                Long.class, kbId);
        jdbcTemplate.update("""
                INSERT INTO kb_document_chunk
                    (doc_id, seq, content, token_count, created_at)
                VALUES (?, 0, 'Stage 5 permission matrix source', 6, ?)
                """, docId, now);

    }

    @AfterAll
    void cleanupFixture() {
        if (kbId <= 0) {
            return;
        }
        List<Long> conversationIds = jdbcTemplate.queryForList(
                "SELECT conversation_id FROM ai_conversation_kb WHERE kb_id = ?",
                Long.class,
                kbId);
        for (Long conversationId : conversationIds) {
            jdbcTemplate.update(
                    "DELETE c FROM ai_message_citation c JOIN ai_message m"
                            + " ON m.id = c.message_id WHERE m.conversation_id = ?",
                    conversationId);
            jdbcTemplate.update(
                    "DELETE FROM ai_message WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update(
                    "DELETE FROM ai_conversation_kb WHERE conversation_id = ?", conversationId);
            jdbcTemplate.update(
                    "DELETE FROM ai_conversation WHERE id = ?", conversationId);
        }
        List<Long> documentIds = jdbcTemplate.queryForList(
                "SELECT id FROM kb_document WHERE kb_id = ?", Long.class, kbId);
        for (Long documentId : documentIds) {
            jdbcTemplate.update(
                    "DELETE FROM kb_ingestion_task WHERE doc_id = ?", documentId);
            jdbcTemplate.update(
                    "DELETE FROM kb_document_chunk WHERE doc_id = ?", documentId);
            jdbcTemplate.update(
                    "DELETE FROM kb_document WHERE id = ?", documentId);
        }
        jdbcTemplate.update("DELETE FROM kb_audit_grant WHERE kb_id = ?", kbId);
        jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ?", kbId);
        jdbcTemplate.update("DELETE FROM kb_knowledge_base WHERE id = ?", kbId);
        jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id IN"
                + " (SELECT id FROM kb_knowledge_base WHERE name = ?)", createdKbName);
        jdbcTemplate.update("DELETE FROM kb_knowledge_base WHERE name = ?", createdKbName);
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE operator_id IN (?, ?, ?)",
                adminId, kbAdminId, viewerId);
        jdbcTemplate.update("DELETE FROM sys_user_login_log WHERE user_id IN (?, ?, ?)",
                adminId, kbAdminId, viewerId);
        jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id IN (?, ?, ?)",
                adminId, kbAdminId, viewerId);
        jdbcTemplate.update("DELETE FROM sys_user WHERE id IN (?, ?, ?)",
                adminId, kbAdminId, viewerId);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        AtomicLong counter = new AtomicLong();
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(values.increment(anyString())).thenAnswer(ignored -> counter.incrementAndGet());
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.delete(anyString())).thenReturn(true);
        when(healthService.check()).thenReturn(new HealthReport(
                "UP",
                "2026-10-31T00:00:00Z",
                List.of(new ComponentHealth("mysql", "UP", 1, null))));
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(filters.toArray(Filter[]::new))
                .build();
    }

    @Test
    @Timeout(180)
    void allEndpointRolesMatchThePermissionMatrix() throws Exception {
        List<EndpointCase> cases = endpointCases();
        List<String> failures = new ArrayList<>();

        for (EndpointCase endpoint : cases) {
            for (Actor actor : actors()) {
                String token = actor.publicLogin() ? null : login(actor.username(), actor.password());
                RequestBuilder request = endpoint.request().get();
                if (token != null) {
                    request = withAuthorization(request, token);
                }
                MvcResult result = mockMvc.perform(request).andReturn();
                int status = result.getResponse().getStatus();
                boolean expectedAllowed = switch (endpoint.access()) {
                    case PUBLIC, AUTHENTICATED, READ_KB -> true;
                    case MANAGE_KB -> !"USER".equals(actor.role());
                    case ADMIN_ONLY -> "SYS_ADMIN".equals(actor.role());
                };

                if (expectedAllowed) {
                    if (status == 401 || status == 403) {
                        failures.add(endpoint.id() + " " + actor.role()
                                + " unexpectedly denied with HTTP " + status);
                    }
                } else if (status != 403) {
                    failures.add(endpoint.id() + " " + actor.role()
                            + " expected HTTP 403 but got " + status);
                }

                if (endpoint.access() != Access.PUBLIC && status == 401) {
                    failures.add(endpoint.id() + " " + actor.role()
                            + " lost an authenticated token");
                }
            }
        }

        assertThat(failures).as("permission matrix mismatches").isEmpty();
        assertThat(cases).hasSizeGreaterThanOrEqualTo(40);
    }

    private List<EndpointCase> endpointCases() throws Exception {
        byte[] json = JSON.writeValueAsBytes(Map.of());
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);
        List<EndpointCase> cases = new ArrayList<>();

        cases.add(publicCase("auth.login", () -> post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bytes(Map.of("username", ADMIN_USERNAME, "password", TEST_PASSWORD)))));
        cases.add(authCase("auth.me", () -> get("/auth/me")));
        cases.add(authCase("auth.logout", () -> post("/auth/logout")));
        cases.add(authCase("auth.password", () -> put("/auth/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bytes(Map.of("currentPassword", "wrong-pass", "newPassword", "New@123456")))));

        cases.add(adminCase("users.list", () -> get("/admin/users")));
        cases.add(adminCase("users.get", () -> get("/admin/users/999999")));
        cases.add(adminCase("users.create", () -> post("/admin/users")
                .contentType(MediaType.APPLICATION_JSON).content(json)));
        cases.add(adminCase("users.update", () -> put("/admin/users/999999")
                .contentType(MediaType.APPLICATION_JSON).content(json)));
        cases.add(adminCase("users.status", () -> patch("/admin/users/999999/status")
                .contentType(MediaType.APPLICATION_JSON).content(json)));
        cases.add(adminCase("users.roles", () -> put("/admin/users/999999/roles")
                .contentType(MediaType.APPLICATION_JSON).content(json)));
        cases.add(adminCase("users.password", () -> put("/admin/users/999999/password")
                .contentType(MediaType.APPLICATION_JSON).content(json)));
        cases.add(adminCase("users.delete", () -> delete("/admin/users/999999")));

        cases.add(adminCase("models.list", () -> get("/admin/models")));
        cases.add(adminCase("models.get", () -> get("/admin/models/999999")));
        cases.add(adminCase("models.create", () -> post("/admin/models")
                .contentType(MediaType.APPLICATION_JSON).content(json)));
        cases.add(adminCase("models.update", () -> put("/admin/models/999999")
                .contentType(MediaType.APPLICATION_JSON).content(json)));
        cases.add(adminCase("models.delete", () -> delete("/admin/models/999999")));
        cases.add(adminCase("models.test", () -> post("/admin/models/999999/test")));
        cases.add(adminCase("models.stats", () -> get("/admin/models/stats")));
        cases.add(adminCase("audit.list", () -> get("/admin/audit-logs")));
        cases.add(adminCase("system.status", () -> get("/admin/system/status")));

        cases.add(authCase("workspace.summary", () -> get("/workspace/summary")));
        cases.add(authCase("kb.list", () -> get("/knowledge-bases")));
        cases.add(authCase("kb.create", () -> post("/knowledge-bases")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bytes(Map.of("name", createdKbName)))));
        cases.add(readCase("kb.get", () -> get("/knowledge-bases/{kbId}", kbId)));
        cases.add(readCase("members.list",
                () -> get("/knowledge-bases/{kbId}/members", kbId)));
        cases.add(manageCase("members.create", () -> post("/knowledge-bases/{kbId}/members", kbId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bytes(Map.of("userId", viewerId, "role", "VIEWER")))));
        cases.add(manageCase("members.candidates",
                () -> get("/knowledge-bases/{kbId}/members/candidates", kbId)));
        cases.add(manageCase("members.update",
                () -> put("/knowledge-bases/{kbId}/members/999999", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bytes(Map.of("role", "VIEWER")))));
        cases.add(manageCase("members.delete",
                () -> delete("/knowledge-bases/{kbId}/members/999999", kbId)));

        cases.add(manageCase("documents.upload", () -> multipart(
                "/knowledge-bases/{kbId}/documents", kbId).file(emptyFile)));
        cases.add(readCase("documents.list",
                () -> get("/knowledge-bases/{kbId}/documents", kbId)));
        cases.add(readCase("documents.get",
                () -> get("/knowledge-bases/{kbId}/documents/{docId}", kbId, docId)));
        cases.add(readCase("documents.chunks",
                () -> get("/knowledge-bases/{kbId}/documents/{docId}/chunks", kbId, docId)));
        cases.add(readCase("documents.chunk",
                () -> get("/knowledge-bases/{kbId}/documents/{docId}/chunks/0", kbId, docId)));
        cases.add(readCase("documents.download",
                () -> get("/knowledge-bases/{kbId}/documents/{docId}/download", kbId, docId)));
        cases.add(manageCase("documents.delete",
                () -> delete("/knowledge-bases/{kbId}/documents/999999", kbId)));
        cases.add(manageCase("documents.retry",
                () -> post("/knowledge-bases/{kbId}/documents/999999/retry", kbId)));

        cases.add(authCase("qa.conversations", () -> get("/qa/conversations")));
        cases.add(readCase("qa.conversation.create", () -> post("/qa/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bytes(Map.of("kbId", kbId, "title", "Matrix")))));
        cases.add(authCase("qa.conversation.get",
                () -> get("/qa/conversations/999999")));
        cases.add(authCase("qa.conversation.patch",
                () -> patch("/qa/conversations/999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bytes(Map.of("title", "Matrix")))));
        cases.add(authCase("qa.conversation.delete",
                () -> delete("/qa/conversations/999999")));
        cases.add(authCase("qa.citation", () -> get("/qa/citations/999999")));
        cases.add(authCase("qa.ask", () -> post("/qa/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bytes(Map.of("conversationId", 999999, "question", "matrix")))));
        cases.add(authCase("qa.stream", () -> post("/qa/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bytes(Map.of("conversationId", 999999, "question", "matrix")))));

        cases.add(publicCase("health", () -> get("/health")));
        return cases;
    }

    private List<Actor> actors() {
        return List.of(
                new Actor("SYS_ADMIN", ADMIN_USERNAME, TEST_PASSWORD, false),
                new Actor("KB_ADMIN", KB_ADMIN_USERNAME, TEST_PASSWORD, false),
                new Actor("USER", VIEWER_USERNAME, TEST_PASSWORD, false));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bytes(Map.of("username", username, "password", password))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("login for %s", username).isEqualTo(200);
        JsonNode response = JSON.readTree(result.getResponse().getContentAsByteArray());
        return response.path("data").path("token").asText();
    }

    private static byte[] bytes(Object value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("failed to serialize test JSON", ex);
        }
    }

    private static RequestBuilder withAuthorization(RequestBuilder request, String token) {
        String authorization = "Bearer " + token;
        if (request instanceof MockHttpServletRequestBuilder builder) {
            return builder.header("Authorization", authorization);
        }
        if (request instanceof MockMultipartHttpServletRequestBuilder builder) {
            return builder.header("Authorization", authorization);
        }
        throw new IllegalArgumentException("Unsupported request builder: " + request.getClass());
    }

    private void upsertUser(String username, String nickname) {
        jdbcTemplate.update("""
                INSERT INTO sys_user
                    (username, password_hash, nickname, email, status, token_version,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', 0, NOW(3), NOW(3))
                ON DUPLICATE KEY UPDATE password_hash = ?, nickname = ?, status = 'ACTIVE'
                """, username, TEST_HASH, nickname, username + "@knowbase.local",
                TEST_HASH, nickname);
    }

    private void assignRole(int userId, String roleCode) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO sys_user_role (user_id, role_id, granted_by, created_at)
                SELECT ?, id, ?, NOW(3) FROM sys_role WHERE code = ?
                """, userId, userId, roleCode);
    }

    private int userId(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM sys_user WHERE username = ?", Integer.class, username);
    }

    private static EndpointCase publicCase(String id, Supplier<RequestBuilder> request) {
        return new EndpointCase(id, Access.PUBLIC, request);
    }

    private static EndpointCase authCase(String id, Supplier<RequestBuilder> request) {
        return new EndpointCase(id, Access.AUTHENTICATED, request);
    }

    private static EndpointCase adminCase(String id, Supplier<RequestBuilder> request) {
        return new EndpointCase(id, Access.ADMIN_ONLY, request);
    }

    private static EndpointCase readCase(String id, Supplier<RequestBuilder> request) {
        return new EndpointCase(id, Access.READ_KB, request);
    }

    private static EndpointCase manageCase(String id, Supplier<RequestBuilder> request) {
        return new EndpointCase(id, Access.MANAGE_KB, request);
    }

    private enum Access {
        PUBLIC,
        AUTHENTICATED,
        READ_KB,
        MANAGE_KB,
        ADMIN_ONLY
    }

    private record Actor(String role, String username, String password, boolean publicLogin) {
    }

    private record EndpointCase(
            String id,
            Access access,
            Supplier<RequestBuilder> request) {
    }
}
