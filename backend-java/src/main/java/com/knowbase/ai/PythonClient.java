package com.knowbase.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.common.TraceContext;
import com.knowbase.config.AiProperties;
import com.knowbase.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Java -> Python 内部调用（docs/INTERNAL_API.md §0）。
 *
 * <p>三条硬规则：
 * <ul>
 *   <li>每次带 X-Internal-Token + X-Trace-Id；缺 token 启动期就该拦，不在这里凑合；</li>
 *   <li>超时连接 5s、读取 65s，留给 Python 60s 解析上限返回结构化错误；</li>
 *   <li>Python 4xx 透出可读信息给上层定 error_code；Python 不可达统一 DEPENDENCY_UNAVAILABLE。</li>
 * </ul>
 */
@Component
public class PythonClient {

    private static final Logger log = LoggerFactory.getLogger(PythonClient.class);

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String internalToken;
    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public PythonClient(AiProperties aiProperties, SecurityProperties securityProperties) {
        String baseUrl = aiProperties.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("knowbase.python.base-url 未配置");
        }
        int connectTimeout = aiProperties.connectTimeoutMs() == null ? 5000 : aiProperties.connectTimeoutMs();
        int readTimeout = aiProperties.readTimeoutMs() == null ? 65000 : aiProperties.readTimeoutMs();
        if (connectTimeout <= 0 || readTimeout <= 0) {
            throw new IllegalStateException("knowbase.python 超时必须为正整数");
        }
        this.internalToken = securityProperties.internalToken();
        this.baseUrl = baseUrl.trim();
        this.connectTimeoutMs = connectTimeout;
        this.readTimeoutMs = readTimeout;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        log.info("Python 内部客户端已启用：baseUrl={} connectTimeout={}ms readTimeout={}ms",
                this.baseUrl, connectTimeout, readTimeout);
    }

    /** POST /internal/parse：返回 chunk 列表（seq 从 0 连续，content 非空）。 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> parse(long docId, long kbId, String storagePath,
            String fileName, String ext) {
        Map<String, Object> body = Map.of(
                "doc_id", docId,
                "kb_id", kbId,
                "storage_path", storagePath,
                "file_name", fileName,
                "ext", ext);
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/internal/parse")
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header(TraceContext.HEADER, TraceContext.current())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("chunks") instanceof List)) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "解析服务返回异常");
            }
            return (List<Map<String, Object>>) response.get("chunks");
        } catch (ResourceAccessException ex) {
            log.warn("Python parse 不可达 docId={} error={}", docId, ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "解析服务不可用");
        } catch (RestClientResponseException ex) {
            throw remoteFailure("parse", docId, ex);
        }
    }

    /** POST /internal/index：向量化并写入 Qdrant，返回 vector_count。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> index(long docId, long kbId, List<Map<String, Object>> chunks) {
        return index(docId, kbId, chunks, null);
    }

    /** POST /internal/index with an optional encrypted-at-rest DB model selection. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> index(long docId, long kbId, List<Map<String, Object>> chunks,
            Map<String, Object> embeddingModel) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("doc_id", docId);
        body.put("kb_id", kbId);
        body.put("chunks", chunks);
        if (embeddingModel != null) {
            body.put("embedding_model", embeddingModel);
        }
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/internal/index")
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header(TraceContext.HEADER, TraceContext.current())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("vector_count") == null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "向量服务返回异常");
            }
            return response;
        } catch (ResourceAccessException ex) {
            log.warn("Python index 不可达 docId={} error={}", docId, ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "向量服务不可用");
        } catch (RestClientResponseException ex) {
            throw remoteFailure("index", docId, ex);
        }
    }

    /** POST /internal/delete：删除指定文档的所有向量，重复执行保持幂等。 */
    public int delete(long docId) {
        Map<String, Object> body = Map.of("doc_id", docId);
        try {
            Map<?, ?> response = restClient.post()
                    .uri("/internal/delete")
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header(TraceContext.HEADER, TraceContext.current())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("deleted_count") == null) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "删除服务返回异常");
            }
            return ((Number) response.get("deleted_count")).intValue();
        } catch (ResourceAccessException ex) {
            log.warn("Python delete 不可达 docId={} error={}", docId, ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "删除服务不可用");
        } catch (RestClientResponseException ex) {
            throw remoteFailure("delete", docId, ex);
        }
    }

    /**
     * Tests a model endpoint inside the AI service. Address validation is repeated
     * there immediately before the outbound request, sharing the code path used by
     * real chat and embedding calls.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> testModel(Map<String, Object> modelConfig) {
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/internal/model/test")
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header(TraceContext.HEADER, TraceContext.current())
                    .body(modelConfig)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !response.containsKey("success")) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "模型测试服务返回异常");
            }
            return response;
        } catch (ResourceAccessException ex) {
            log.warn("Python model test 不可达 error={}", ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "模型测试服务不可用");
        } catch (RestClientResponseException ex) {
            throw remoteFailure("model-test", 0, ex);
        }
    }

    /** POST /internal/search：kb_ids 由 Java 计算传入；空集合直接回空，不碰 Python。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> search(List<Long> kbIds, String query, int topK, double scoreThreshold) {
        return search(kbIds, query, topK, scoreThreshold, null);
    }

    public Map<String, Object> search(List<Long> kbIds, String query, int topK,
            double scoreThreshold, Map<String, Object> embeddingModel) {
        if (kbIds == null || kbIds.isEmpty()) {
            return Map.of("query", query, "hits", List.of());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kb_ids", kbIds);
        body.put("query", query);
        body.put("top_k", topK);
        body.put("score_threshold", scoreThreshold);
        if (embeddingModel != null) {
            body.put("embedding_model", embeddingModel);
        }
        try {
            Map<String, Object> response = restClient.post()
                    .uri("/internal/search")
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .header(TraceContext.HEADER, TraceContext.current())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (response == null || !(response.get("hits") instanceof List)) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "检索服务返回异常");
            }
            return response;
        } catch (ResourceAccessException ex) {
            log.warn("Python search 不可达 error={}", ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "检索服务不可用");
        } catch (RestClientResponseException ex) {
            throw remoteFailure("search", 0, ex);
        }
    }

    /**
     * Opens the internal SSE endpoint. The returned handle can be closed from
     * another thread; closing either cancels the pending HTTP request or closes
     * its response body, which propagates cancellation to Python and onward to
     * the upstream model connection.
     */
    public ChatStream openChatStream(Map<String, Object> body) {
        try {
            String requestBody = ERROR_MAPPER.writeValueAsString(body);
            AtomicReference<HttpURLConnection> connection = new AtomicReference<>();
            CompletableFuture<StreamResponse> future = new CompletableFuture<>();
            Thread.ofVirtual().name("qa-python-stream").start(() -> {
                try {
                    StreamResponse response = connectChatStream(requestBody, connection);
                    if (future.isCancelled()) {
                        ChatStream.closeQuietly(response.body());
                        HttpURLConnection current = connection.get();
                        if (current != null) {
                            current.disconnect();
                        }
                        return;
                    }
                    future.complete(response);
                } catch (Throwable ex) {
                    future.completeExceptionally(ex);
                }
            });
            return new ChatStream(future, connection);
        } catch (Exception ex) {
            log.warn("Python chat stream 请求创建失败 error={}", ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "问答服务不可用");
        }
    }

    private StreamResponse connectChatStream(
            String requestBody, AtomicReference<HttpURLConnection> connectionRef) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(baseUrl + "/internal/chat/stream")
                    .toURL()
                    .openConnection();
            connectionRef.set(connection);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty(INTERNAL_TOKEN_HEADER, internalToken);
            connection.setRequestProperty(TraceContext.HEADER, TraceContext.current());
            connection.setRequestProperty(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            connection.setRequestProperty(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
            byte[] payload = requestBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int status = connection.getResponseCode();
            InputStream responseBody = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            return new StreamResponse(
                    status,
                    responseBody == null ? InputStream.nullInputStream() : responseBody);
        } catch (IOException ex) {
            if (connection != null) {
                connection.disconnect();
            }
            throw ex;
        }
    }

    private static AiRemoteException remoteFailure(String operation, long docId, RestClientResponseException ex) {
        String code = "DEPENDENCY_UNAVAILABLE";
        String message = "AI 服务处理失败";
        try {
            JsonNode body = ERROR_MAPPER.readTree(ex.getResponseBodyAsString());
            if (body != null) {
                if (body.hasNonNull("code")) {
                    code = body.get("code").asText(code);
                }
                if (body.hasNonNull("message")) {
                    message = body.get("message").asText(message);
                }
            }
        } catch (Exception ignored) {
            // Python 返回体异常时仍保留 HTTP 状态供排查，不把原始响应塞给前端。
        }
        log.warn("Python {} 返回错误 docId={} status={} code={}",
                operation, docId, ex.getStatusCode().value(), code);
        return new AiRemoteException(code, truncate(message, 1800));
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public static final class ChatStream implements AutoCloseable {

        private final CompletableFuture<StreamResponse> future;
        private final AtomicReference<HttpURLConnection> connection;
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile InputStream body;

        private ChatStream(
                CompletableFuture<StreamResponse> future,
                AtomicReference<HttpURLConnection> connection) {
            this.future = future;
            this.connection = connection;
        }

        public void consume(Consumer<AiStreamEvent> consumer, BooleanSupplier cancelled) {
            StreamResponse response;
            try {
                response = future.join();
            } catch (CompletionException ex) {
                if (!closed.get() && !cancelled.getAsBoolean()) {
                    log.warn("Python chat stream 不可达 error={}",
                            ex.getCause() == null ? ex.getClass().getSimpleName()
                                    : ex.getCause().getClass().getSimpleName());
                    throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "问答服务不可用");
                }
                return;
            }
            if (closed.get() || cancelled.getAsBoolean()) {
                closeQuietly(response.body());
                disconnect();
                return;
            }
            if (response.status() >= 400) {
                throw remoteStreamFailure(response);
            }

            body = response.body();
            String eventName = "message";
            StringBuilder data = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while (!closed.get() && !cancelled.getAsBoolean()
                        && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            dispatch(eventName, data, consumer);
                            eventName = "message";
                            data.setLength(0);
                        }
                    } else if (line.startsWith("event:")) {
                        eventName = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(line.substring("data:".length()).trim());
                    }
                }
                if (!closed.get() && !cancelled.getAsBoolean() && data.length() > 0) {
                    dispatch(eventName, data, consumer);
                }
            } catch (IOException ex) {
                if (!closed.get() && !cancelled.getAsBoolean()) {
                    log.warn("Python chat stream 读取中断 error={}", ex.getClass().getSimpleName());
                    throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "问答流中断");
                }
            } finally {
                closeQuietly(body);
                disconnect();
            }
        }

        @SuppressWarnings("unchecked")
        private static void dispatch(
                String eventName, StringBuilder data, Consumer<AiStreamEvent> consumer) {
            try {
                Map<String, Object> payload = ERROR_MAPPER.readValue(data.toString(), Map.class);
                consumer.accept(new AiStreamEvent(eventName, payload));
            } catch (IOException ex) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "问答流格式错误");
            }
        }

        private static AiRemoteException remoteStreamFailure(StreamResponse response) {
            String code = "DEPENDENCY_UNAVAILABLE";
            String message = "AI 服务处理失败";
            try (InputStream errorBody = response.body()) {
                Map<String, Object> payload = ERROR_MAPPER.readValue(
                        errorBody.readAllBytes(), Map.class);
                if (payload.get("code") != null) {
                    code = String.valueOf(payload.get("code"));
                }
                if (payload.get("message") != null) {
                    message = String.valueOf(payload.get("message"));
                }
            } catch (IOException ignored) {
                // Keep the safe generic message when the error body is malformed.
            }
            return new AiRemoteException(code, message);
        }

        @Override
        public void close() {
            closed.set(true);
            future.cancel(true);
            disconnect();
            closeQuietly(body);
        }

        private void disconnect() {
            HttpURLConnection current = connection.get();
            if (current != null) {
                current.disconnect();
            }
        }

        private static void closeQuietly(InputStream stream) {
            if (stream == null) {
                return;
            }
            try {
                stream.close();
            } catch (IOException ignored) {
                // Best effort; the stream is already unusable.
            }
        }
    }

    private record StreamResponse(int status, InputStream body) {
    }

    public record AiStreamEvent(String event, Map<String, Object> data) {
        public AiStreamEvent {
            data = data == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(data));
        }
    }
}
