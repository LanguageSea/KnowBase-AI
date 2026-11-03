package com.knowbase.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.WebApplicationContext;

/**
 * Opt-in full-chain test for Stage 3. It uses the real MySQL/Redis/Python
 * services and MockMvc avoids starting another HTTP connector.
 */
@EnabledIfEnvironmentVariable(named = "KNOWBASE_STAGE3_IT", matches = "(?i)true")
@SpringBootTest
class Stage3RagIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_PASSWORD = "Admin@123456";
    private static final Duration INGEST_TIMEOUT = Duration.ofSeconds(40);

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
    void uploadIndexAskTraceCitationConversationAndGeneralFallback() throws Exception {
        String token = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long kbId = createKnowledgeBase(token, "stage3-it-" + suffix, "Stage 3 integration");
        long emptyKbId = createKnowledgeBase(token, "stage3-empty-" + suffix, "Empty retrieval scope");

        String marker = "KB" + suffix;
        byte[] source = sampleDocument(marker).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "stage3-" + suffix + ".txt", "text/plain", source);
        JsonNode upload = json(mockMvc.perform(multipart(
                        "/knowledge-bases/{kbId}/documents", kbId)
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        long docId = upload.path("data").path("id").asLong();
        assertThat(docId).isPositive();

        JsonNode indexed = awaitDocument(token, kbId, docId);
        assertThat(indexed.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(indexed.path("taskStatus").asText()).isEqualTo("SUCCESS");
        assertThat(indexed.path("chunkCount").asInt()).isPositive();

        JsonNode answer = ask(token, kbId, null, marker + " 报销多久到账？");
        JsonNode answerData = answer.path("data");
        long conversationId = answerData.path("conversationId").asLong();
        assertThat(answerData.path("answer").asText()).contains("报销");
        assertThat(answerData.path("noAnswer").asBoolean()).isFalse();
        assertThat(answerData.path("citations")).isNotEmpty();
        JsonNode firstCitation = answerData.path("citations").get(0);
        assertThat(firstCitation.path("docId").asLong()).isEqualTo(docId);

        JsonNode detail = json(mockMvc.perform(get(
                        "/qa/conversations/{id}", conversationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode messageCitations = detail.path("data").path("messages").get(1).path("citations");
        assertThat(messageCitations).isNotEmpty();
        long persistedCitationId = messageCitations.get(0).path("id").asLong();
        assertThat(persistedCitationId).isPositive();

        JsonNode citation = json(mockMvc.perform(get(
                        "/qa/citations/{id}", persistedCitationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(citation.path("data").path("docId").asLong()).isEqualTo(docId);
        assertThat(citation.path("data").path("content").asText()).contains(marker);

        JsonNode renamed = json(mockMvc.perform(patch(
                        "/qa/conversations/{id}", conversationId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of("title", "Stage 3 验收会话"))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(renamed.path("data").path("title").asText()).isEqualTo("Stage 3 验收会话");

        JsonNode conversations = json(mockMvc.perform(get("/qa/conversations")
                        .param("kbId", String.valueOf(kbId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(conversations.path("data").path("items"))
                .anySatisfy(item -> assertThat(item.path("id").asLong()).isEqualTo(conversationId));

        JsonNode fallback = ask(token, emptyKbId, null, "Java 的垃圾回收有哪些算法？");
        assertThat(fallback.path("data").path("answer").asText())
                .startsWith("知识库中未找到相关依据。");
        assertThat(fallback.path("data").path("noAnswer").asBoolean()).isFalse();
        assertThat(fallback.path("data").path("citations")).isEmpty();
        assertThat(fallback.path("data").path("chatProvider").asText()).isNotEqualTo("none");

        MvcResult streamStarted = mockMvc.perform(post("/qa/stream")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of(
                                "kbId", kbId,
                                "question", marker + " 小额费用由谁审批？"))))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult streamDone = mockMvc.perform(asyncDispatch(streamStarted))
                .andExpect(status().isOk())
                .andReturn();
        String sse = streamDone.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(sse).contains("event:meta", "event:retrieval", "event:delta", "event:done");
        assertThat(sse).doesNotContain("event:error");

        mockMvc.perform(delete("/qa/conversations/{id}", conversationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        JsonNode afterDelete = json(mockMvc.perform(get(
                        "/qa/conversations/{id}", conversationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andReturn());
        assertThat(afterDelete.path("code").asInt()).isEqualTo(40401);

        mockMvc.perform(delete("/knowledge-bases/{kbId}/documents/{docId}", kbId, docId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        awaitDocumentStatus(token, kbId, docId, "DELETED");
        cleanupKnowledgeBase(kbId);
        cleanupKnowledgeBase(emptyKbId);
    }

    private String login() throws Exception {
        JsonNode body = json(mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of(
                                "username", "admin",
                                "password", ADMIN_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn());
        String token = body.path("data").path("token").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private long createKnowledgeBase(String token, String name, String description) throws Exception {
        JsonNode body = json(mockMvc.perform(post("/knowledge-bases")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(Map.of(
                                "name", name,
                                "description", description))))
                .andExpect(status().isOk())
                .andReturn());
        long id = body.path("data").path("id").asLong();
        assertThat(id).isPositive();
        return id;
    }

    private JsonNode ask(String token, long kbId, Long conversationId, String question) throws Exception {
        Map<String, Object> request = conversationId == null
                ? Map.of("kbId", kbId, "question", question)
                : Map.of("kbId", kbId, "conversationId", conversationId, "question", question);
        return json(mockMvc.perform(post("/qa/ask")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private JsonNode awaitDocument(String token, long kbId, long docId) throws Exception {
        long deadline = System.nanoTime() + INGEST_TIMEOUT.toNanos();
        JsonNode last = null;
        while (System.nanoTime() < deadline) {
            last = json(mockMvc.perform(get(
                            "/knowledge-bases/{kbId}/documents/{docId}", kbId, docId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn()).path("data");
            String status = last.path("status").asText();
            if ("ACTIVE".equals(status) || "FAILED".equals(status)) {
                return last;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("document ingestion timed out: " + last);
    }

    private void awaitDocumentStatus(
            String token, long kbId, long docId, String expectedStatus) throws Exception {
        long deadline = System.nanoTime() + INGEST_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            MvcResult result = mockMvc.perform(get(
                            "/knowledge-bases/{kbId}/documents/{docId}", kbId, docId)
                            .header("Authorization", "Bearer " + token))
                    .andReturn();
            if ("DELETED".equals(expectedStatus) && result.getResponse().getStatus() == 404) {
                return;
            }
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            JsonNode data = json(result).path("data");
            if (expectedStatus.equals(data.path("status").asText())) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("document did not reach " + expectedStatus);
    }

    private void cleanupKnowledgeBase(long kbId) {
        List<Long> conversationIds = jdbcTemplate.queryForList(
                "SELECT conversation_id FROM ai_conversation_kb WHERE kb_id = ?",
                Long.class,
                kbId);
        for (Long conversationId : conversationIds) {
            jdbcTemplate.update(
                    "DELETE c FROM ai_message_citation c"
                            + " JOIN ai_message m ON m.id = c.message_id"
                            + " WHERE m.conversation_id = ?",
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
        jdbcTemplate.update("DELETE FROM kb_member WHERE kb_id = ?", kbId);
        jdbcTemplate.update("DELETE FROM kb_knowledge_base WHERE id = ?", kbId);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private static String sampleDocument(String marker) {
        return """
                %s 公司报销制度

                员工因公出差产生的交通费、住宿费，应当在费用发生后十五个工作日内提交报销申请。
                单笔金额低于五百元的小额费用，可以由直属主管直接审批。
                报销款项在审批通过后的五个工作日内打入员工工资卡。
                """
                .formatted(marker);
    }
}
