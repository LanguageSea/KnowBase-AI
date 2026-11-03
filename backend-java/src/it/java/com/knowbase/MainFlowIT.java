package com.knowbase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowbase.health.HealthService;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * T5.4: upload -> real Python parse/index -> Qdrant search -> cited answer.
 *
 * <p>MySQL, Qdrant and the FastAPI service are disposable containers. The Java
 * application itself runs in the test JVM, which keeps MockMvc deterministic
 * while still exercising the complete cross-service path.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.profiles.active=test",
        "knowbase.tasks.poll-interval-ms=500",
        "knowbase.tasks.stale-scan-interval-ms=600000",
        "knowbase.tasks.delete-compensation-interval-ms=600000",
        "knowbase.python.connect-timeout-ms=5000",
        "knowbase.python.read-timeout-ms=30000"
})
class MainFlowIT {

    private static final String INTERNAL_TOKEN = "S5-main-flow-shared-key-abcdefghijklmnopqrstuvwxyz";
    private static final String ADMIN_PASSWORD = "Admin@123456";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Network NETWORK = Network.newNetwork();
    private static final Path UPLOAD_DIR = Path.of(
            System.getProperty("java.io.tmpdir"), "knowbase-main-flow-" + UUID.randomUUID());

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("knowbase_it")
            .withUsername("knowbase")
            .withPassword("test-password")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci");

    @Container
    static final GenericContainer<?> QDRANT = new GenericContainer<>(
            DockerImageName.parse("qdrant/qdrant:v1.20.0"))
            .withNetwork(NETWORK)
            .withNetworkAliases("qdrant")
            .withExposedPorts(6333)
            .waitingFor(Wait.forHttp("/readyz").forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(60)));

    @Container
    static final GenericContainer<?> PYTHON = new GenericContainer<>(
            new ImageFromDockerfile("knowbase-ai-python-it", false)
                    .withDockerfile(Path.of("..", "backend-python", "Dockerfile"))
                    .withFileFromPath(".", Path.of("..")))
            .withNetwork(NETWORK)
            .withNetworkAliases("ai-service")
            .withExposedPorts(8090)
            .dependsOn(QDRANT)
            .withEnv("APP_ENV", "dev")
            .withEnv("INTERNAL_TOKEN", INTERNAL_TOKEN)
            .withEnv("QDRANT_URL", "http://qdrant:6333")
            .withEnv("QDRANT_COLLECTION", "knowbase_chunks_it")
            .withEnv("EMBEDDING_DIM", "1024")
            .withEnv("LLM_BASE_URL", "")
            .withEnv("LLM_API_KEY", "")
            .waitingFor(Wait.forHttp("/internal/health")
                    .withHeader("X-Internal-Token", INTERNAL_TOKEN)
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofSeconds(90)));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("knowbase.python.base-url",
                () -> "http://" + PYTHON.getHost() + ":" + PYTHON.getMappedPort(8090));
        registry.add("knowbase.storage.upload-dir", UPLOAD_DIR::toString);
        registry.add("knowbase.security.master-key",
                () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        registry.add("knowbase.security.internal-token", () -> INTERNAL_TOKEN);
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private List<Filter> filters;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private HealthService healthService;

    private MockMvc mockMvc;

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
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .addFilters(filters.toArray(Filter[]::new))
                .build();
    }

    @Test
    @Timeout(120)
    void uploadIndexSearchAndCitationFlowWorksAcrossContainers() throws Exception {
        String token = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String marker = "KB" + suffix;
        long kbId = createKnowledgeBase(token, "main-flow-" + suffix);

        String text = """
                %s 公司差旅报销制度
                员工因公出差产生的交通费和住宿费，应在费用发生后十五个工作日内提交报销申请。
                单笔超过五千元的费用，需要财务负责人与总经理双重审批。
                报销款在审批通过后的五个工作日内支付到员工工资卡。
                """.formatted(marker);
        MockMultipartFile file = new MockMultipartFile(
                "file", "main-flow-" + suffix + ".txt", "text/plain",
                text.getBytes(StandardCharsets.UTF_8));

        JsonNode uploaded = json(mockMvc.perform(multipart(
                        "/knowledge-bases/{kbId}/documents", kbId)
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        long docId = uploaded.path("id").asLong();
        assertThat(docId).isPositive();

        JsonNode indexed = awaitDocument(token, kbId, docId);
        assertThat(indexed.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(indexed.path("chunkCount").asInt()).isPositive();

        JsonNode answer = json(mockMvc.perform(post("/qa/ask")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of(
                                "kbId", kbId,
                                "question", marker + " 报销款多久到账？"))))
                .andExpect(status().isOk())
                .andReturn()).path("data");

        assertThat(answer.path("answer").asText()).contains("报销");
        assertThat(answer.path("citations")).isNotEmpty();
        JsonNode citation = answer.path("citations").get(0);
        assertThat(citation.path("docId").asLong()).isEqualTo(docId);
        assertThat(citation.path("chunkId").asLong()).isPositive();
        assertThat(citation.path("quote").asText()).contains(marker);
    }

    private String login() throws Exception {
        JsonNode response = json(mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of(
                                "username", "admin",
                                "password", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn());
        String token = response.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private long createKnowledgeBase(String token, String name) throws Exception {
        JsonNode response = json(mockMvc.perform(post("/knowledge-bases")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of(
                                "name", name,
                                "description", "Stage 5 Testcontainers main flow"))))
                .andExpect(status().isOk())
                .andReturn());
        long kbId = response.path("data").path("id").asLong();
        assertThat(kbId).isPositive();
        return kbId;
    }

    private JsonNode awaitDocument(String token, long kbId, long docId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = json(mockMvc.perform(get(
                            "/knowledge-bases/{kbId}/documents/{docId}", kbId, docId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn()).path("data");
            String statusValue = last.path("status").asText();
            if ("ACTIVE".equals(statusValue) || "FAILED".equals(statusValue)) {
                return last;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("document ingestion timed out: " + last);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
}
