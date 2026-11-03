package com.knowbase.qa;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.knowbase.ai.PythonClient;
import com.knowbase.ai.PythonClient.AiStreamEvent;
import com.knowbase.auth.AuthUser;
import com.knowbase.auth.CurrentUser;
import com.knowbase.auth.UserRepository;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.common.TraceContext;
import com.knowbase.config.QaProperties;
import com.knowbase.kb.AuthzService;
import com.knowbase.model.AiCallLogService;
import com.knowbase.model.ModelConfigService;
import com.knowbase.model.ModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG answer orchestration. Java remains the authority for user identity,
 * knowledge-base scope, persistence and citation integrity. Python receives
 * only the already-authorised kb ids and performs retrieval/context/generation.
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);
    private static final String NO_ANSWER = "未找到相关依据";
    private static final int HISTORY_LIMIT = 20;

    private final AuthzService authzService;
    private final UserRepository userRepository;
    private final QaRepository qaRepository;
    private final PythonClient pythonClient;
    private final ModelConfigService modelConfigService;
    private final AiCallLogService callLogService;
    private final QaRateLimiter rateLimiter;
    private final QaCitationResolver citationResolver;
    private final TransactionTemplate transactionTemplate;
    private final ExecutorService streamExecutor;
    private final int topK;
    private final double scoreThreshold;
    private final int contextTokenBudget;
    private final int maxContextChunks;
    private final long streamTimeoutMs;

    @Autowired
    public QaService(
            AuthzService authzService,
            UserRepository userRepository,
            QaRepository qaRepository,
            PythonClient pythonClient,
            ModelConfigService modelConfigService,
            AiCallLogService callLogService,
            QaRateLimiter rateLimiter,
            QaCitationResolver citationResolver,
            TransactionTemplate transactionTemplate,
            @Qualifier("qaStreamExecutor") ExecutorService streamExecutor,
            QaProperties properties) {
        this.authzService = authzService;
        this.userRepository = userRepository;
        this.qaRepository = qaRepository;
        this.pythonClient = pythonClient;
        this.modelConfigService = modelConfigService;
        this.callLogService = callLogService;
        this.rateLimiter = rateLimiter;
        this.citationResolver = citationResolver;
        this.transactionTemplate = transactionTemplate;
        this.streamExecutor = streamExecutor;

        Integer configuredTopK = properties.topK();
        Double configuredThreshold = properties.scoreThreshold();
        Integer configuredBudget = properties.contextTokenBudget();
        Integer configuredChunks = properties.maxContextChunks();
        Long configuredTimeout = properties.streamTimeoutMs();
        this.topK = configuredTopK == null ? 5 : configuredTopK;
        this.scoreThreshold = configuredThreshold == null ? 0.2 : configuredThreshold;
        this.contextTokenBudget = configuredBudget == null ? 3000 : configuredBudget;
        this.maxContextChunks = configuredChunks == null ? 8 : configuredChunks;
        this.streamTimeoutMs = configuredTimeout == null ? 90000L : configuredTimeout;
        if (topK < 1 || topK > 20) {
            throw new IllegalStateException("knowbase.qa.top-k 必须在 1-20 之间");
        }
        if (scoreThreshold < 0 || scoreThreshold > 1) {
            throw new IllegalStateException("knowbase.qa.score-threshold 必须在 0-1 之间");
        }
        if (contextTokenBudget < 200 || contextTokenBudget > 12000) {
            throw new IllegalStateException("knowbase.qa.context-token-budget 必须在 200-12000 之间");
        }
        if (maxContextChunks < 1 || maxContextChunks > 20) {
            throw new IllegalStateException("knowbase.qa.max-context-chunks 必须在 1-20 之间");
        }
        if (streamTimeoutMs < 1000) {
            throw new IllegalStateException("knowbase.qa.stream-timeout-ms 必须至少为 1000");
        }
    }

    /** Backward-compatible constructor for focused unit tests without model persistence. */
    public QaService(
            AuthzService authzService,
            UserRepository userRepository,
            QaRepository qaRepository,
            PythonClient pythonClient,
            QaRateLimiter rateLimiter,
            QaCitationResolver citationResolver,
            TransactionTemplate transactionTemplate,
            ExecutorService streamExecutor,
            QaProperties properties) {
        this(authzService, userRepository, qaRepository, pythonClient, null, null,
                rateLimiter, citationResolver, transactionTemplate, streamExecutor, properties);
    }

    /**
     * Non-streaming compatibility endpoint. It follows the same RAG path as
     * SSE so prompt, short-circuit and citation semantics cannot diverge.
     */
    public QaDtos.AskResponse ask(QaDtos.AskRequest request) {
        PreparedAsk prepared = prepare(request.kbId(), request.conversationId(), request.question());
        GeneratedAnswer generated = generate(
                prepared, EventSink.NOOP, new AtomicBoolean(false), ignored -> { });
        return new QaDtos.AskResponse(
                prepared.conversationId(),
                prepared.assistantMessageId(),
                generated.answer(),
                generated.noAnswer(),
                generated.citations(),
                generated.chatProvider(),
                generated.embeddingProvider(),
                generated.latencyMs());
    }

    /**
     * Returns an SseEmitter immediately after validating auth and persisting the
     * user/placeholder messages. Generation runs on a virtual thread so the
     * servlet request thread can return the streaming response.
     */
    public SseEmitter stream(QaDtos.StreamRequest request) {
        PreparedAsk prepared = prepare(request.kbId(), request.conversationId(), request.question());
        String traceId = TraceContext.current();
        SseEmitter emitter = new SseEmitter(streamTimeoutMs);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<PythonClient.ChatStream> upstream = new AtomicReference<>();
        Runnable cancel = () -> {
            cancelled.set(true);
            PythonClient.ChatStream current = upstream.get();
            if (current != null) {
                current.close();
            }
        };
        emitter.onCompletion(cancel);
        emitter.onTimeout(cancel);
        emitter.onError(ignored -> cancel.run());

        streamExecutor.execute(() -> {
            if (!traceId.isEmpty()) {
                MDC.put(TraceContext.MDC_KEY, traceId);
            }
            EventSink sink = (event, payload) -> emitter.send(
                    SseEmitter.event()
                            .name(event)
                            .data(payload, MediaType.APPLICATION_JSON));
            try {
                sink.emit("meta", mapOf(
                        "conversationId", prepared.conversationId(),
                        "userMessageId", prepared.userMessageId(),
                        "messageId", prepared.assistantMessageId(),
                        "title", prepared.title()));
                GeneratedAnswer generated = generate(prepared, sink, cancelled, upstream::set);
                if (cancelled.get() || "CANCELLED".equals(generated.status())) {
                    sink.emit("cancelled", mapOf(
                            "conversationId", prepared.conversationId(),
                            "messageId", prepared.assistantMessageId(),
                            "answer", generated.answer(),
                            "status", "CANCELLED"));
                } else {
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("conversationId", prepared.conversationId());
                    done.put("messageId", prepared.assistantMessageId());
                    done.put("answer", generated.answer());
                    done.put("noAnswer", generated.noAnswer());
                    done.put("citations", generated.citations());
                    done.put("chatProvider", generated.chatProvider());
                    done.put("embeddingProvider", generated.embeddingProvider());
                    done.put("latencyMs", generated.latencyMs());
                    done.put("status", generated.status());
                    sink.emit("done", done);
                }
                emitter.complete();
            } catch (ClientDisconnectedException ex) {
                cancel.run();
                emitter.complete();
            } catch (BusinessException ex) {
                try {
                    sink.emit("error", errorPayload(ex));
                } catch (Exception ignored) {
                    // Client is already gone.
                }
                emitter.complete();
            } catch (Exception ex) {
                log.error("SSE 问答未处理异常 conversationId={}",
                        prepared.conversationId(), ex);
                try {
                    sink.emit("error", mapOf(
                            "code", ErrorCode.INTERNAL_ERROR.code(),
                            "message", "问答生成失败，请稍后重试",
                            "traceId", traceId));
                } catch (Exception ignored) {
                    // Client is already gone.
                }
                emitter.complete();
            } finally {
                MDC.remove(TraceContext.MDC_KEY);
            }
        });
        return emitter;
    }

    private PreparedAsk prepare(Long kbId, Long conversationId, String question) {
        AuthUser user = CurrentUser.require();
        String normalizedQuestion = validateQuestion(question);
        List<Long> kbIds;
        List<Map<String, String>> history = new ArrayList<>();
        QaRepository.ConversationRow existing = null;
        if (conversationId == null) {
            if (kbId == null) {
                kbIds = authzService.readableKbIds(user.id());
            } else {
                if (!authzService.canReadKb(user.id(), kbId)) {
                    throw new BusinessException(ErrorCode.FORBIDDEN, "无权读取该知识库");
                }
                kbIds = List.of(kbId);
            }
        } else {
            existing = qaRepository.findConversation(conversationId, user.id());
            if (existing == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
            }
            Set<Long> readable = new HashSet<>(authzService.readableKbIds(user.id()));
            kbIds = qaRepository.findConversationKbIds(conversationId).stream()
                    .filter(readable::contains)
                    .toList();
            for (QaRepository.MessageRow row : qaRepository.listRecentHistory(
                    conversationId, HISTORY_LIMIT)) {
                history.add(Map.of(
                        "role", row.role().toLowerCase(),
                        "content", row.content()));
            }
        }

        rateLimiter.check(user.id());
        LocalDateTime now = LocalDateTime.now();
        String title = existing == null ? autoTitle(normalizedQuestion) : existing.title();
        final boolean creatingConversation = existing == null;
        final long existingConversationId = existing == null ? 0L : existing.id();
        PreparedAsk prepared = transactionTemplate.execute(status -> {
            long resolvedConversationId;
            if (creatingConversation) {
                resolvedConversationId = qaRepository.insertConversation(user.id(), title, now);
                for (Long id : kbIds) {
                    qaRepository.insertConversationKb(resolvedConversationId, id, now);
                }
            } else {
                resolvedConversationId = existingConversationId;
            }
            long userMessageId = qaRepository.insertMessage(
                    resolvedConversationId, "USER", normalizedQuestion,
                    "OK", null, null, null, now);
            long assistantMessageId = qaRepository.insertMessage(
                    resolvedConversationId, "ASSISTANT", "",
                    "STREAMING", null, null, null, now);
            return new PreparedAsk(
                    user.id(),
                    resolvedConversationId,
                    title,
                    normalizedQuestion,
                    List.copyOf(kbIds),
                    List.copyOf(history),
                    userMessageId,
                    assistantMessageId,
                    System.currentTimeMillis());
        });
        if (prepared == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "创建问答会话失败");
        }
        return prepared;
    }

    private GeneratedAnswer generate(
            PreparedAsk prepared,
            EventSink sink,
            AtomicBoolean cancelled,
            Consumer<PythonClient.ChatStream> upstreamConsumer) {
        StringBuilder streamedAnswer = new StringBuilder();
        AtomicReference<Map<String, Object>> finalEvent = new AtomicReference<>();
        AtomicReference<String> chatProvider = new AtomicReference<>("");
        AtomicReference<String> embeddingProvider = new AtomicReference<>("");
        AtomicInteger deltaCount = new AtomicInteger();
        Set<String> retrievedKeys = new HashSet<>();
        boolean[] noAnswer = {false};
        long modelStarted = System.nanoTime();

        ModelConfigService.RuntimeSelection embeddingModel = null;
        ModelConfigService.RuntimeSelection chatModel = null;
        try {
            if (modelConfigService != null) {
                embeddingModel = modelConfigService.runtimeFor(ModelType.EMBEDDING).orElse(null);
                chatModel = modelConfigService.runtimeFor(ModelType.CHAT).orElse(null);
            }
        } catch (BusinessException ex) {
            persist(prepared, streamedAnswer.toString(), "FAILED",
                    "", "", String.valueOf(ex.getErrorCode().code()), List.of(), false);
            throw ex;
        }

        Map<String, Object> request = new HashMap<>();
        request.put("kb_ids", prepared.kbIds());
        request.put("question", prepared.question());
        request.put("history", prepared.history());
        request.put("top_k", topK);
        request.put("score_threshold", scoreThreshold);
        request.put("context_token_budget", contextTokenBudget);
        request.put("max_context_chunks", maxContextChunks);
        if (embeddingModel != null) {
            request.put("embedding_model", embeddingModel.payload());
        }
        if (chatModel != null) {
            request.put("chat_model", chatModel.payload());
        }

        try (PythonClient.ChatStream stream = pythonClient.openChatStream(request)) {
            upstreamConsumer.accept(stream);
            stream.consume(event -> {
                if (cancelled.get()) {
                    return;
                }
                try {
                    switch (event.event()) {
                        case "retrieval" -> handleRetrieval(
                                event, sink, retrievedKeys, chatProvider, embeddingProvider);
                        case "delta" -> {
                            String text = stringValue(event.data().get("text"));
                            if (!text.isEmpty()) {
                                streamedAnswer.append(text);
                                sink.emit("delta", mapOf("text", text));
                                if (deltaCount.incrementAndGet() % 20 == 0) {
                                    ensureUserActive(prepared.userId());
                                }
                            }
                        }
                        case "no_answer" -> {
                            noAnswer[0] = true;
                            sink.emit("no_answer", event.data());
                        }
                        case "done" -> finalEvent.set(event.data());
                        case "error" -> throw remoteEventError(event.data());
                        default -> {
                            // Unknown events are ignored for forward compatibility.
                        }
                    }
                } catch (IOException ex) {
                    throw new ClientDisconnectedException(ex);
                }
            }, cancelled::get);
            ensureUserActive(prepared.userId());
        } catch (ClientDisconnectedException ex) {
            cancelled.set(true);
            recordModelCalls(embeddingModel, chatModel, modelStarted,
                    true, null, isChatAttempted(chatProvider.get()), false, "CLIENT_DISCONNECTED");
            return persist(prepared, streamedAnswer.toString(), "CANCELLED",
                    chatProvider.get(), embeddingProvider.get(),
                    "CLIENT_DISCONNECTED", List.of(), false);
        } catch (BusinessException ex) {
            boolean cancelStatus = cancelled.get()
                    || ex.getErrorCode() == ErrorCode.UNAUTHORIZED
                    || ex.getErrorCode() == ErrorCode.QA_RATE_LIMITED;
            String failureStatus = ex.getErrorCode() == ErrorCode.EMPTY_COMPLETION
                    ? "EMPTY_COMPLETION"
                    : "FAILED";
            recordModelCalls(embeddingModel, chatModel, modelStarted,
                    false, String.valueOf(ex.getErrorCode().code()),
                    isChatAttempted(chatProvider.get()), false,
                    String.valueOf(ex.getErrorCode().code()));
            persist(prepared, streamedAnswer.toString(),
                    cancelStatus ? "CANCELLED" : failureStatus,
                    chatProvider.get(), embeddingProvider.get(),
                    String.valueOf(ex.getErrorCode().code()), List.of(), false);
            throw ex;
        } catch (RuntimeException ex) {
            recordModelCalls(embeddingModel, chatModel, modelStarted,
                    false, "INTERNAL_ERROR", isChatAttempted(chatProvider.get()), false,
                    "INTERNAL_ERROR");
            persist(prepared, streamedAnswer.toString(), "FAILED",
                    chatProvider.get(), embeddingProvider.get(),
                    "INTERNAL_ERROR", List.of(), false);
            throw ex;
        }

        if (cancelled.get()) {
            recordModelCalls(embeddingModel, chatModel, modelStarted,
                    true, null, isChatAttempted(chatProvider.get()), false, "USER_CANCELLED");
            return persist(prepared, streamedAnswer.toString(), "CANCELLED",
                    chatProvider.get(), embeddingProvider.get(),
                    "USER_CANCELLED", List.of(), false);
        }
        Map<String, Object> done = finalEvent.get();
        if (done == null) {
            BusinessException error = new BusinessException(
                    ErrorCode.DEPENDENCY_UNAVAILABLE, "问答流提前结束");
            recordModelCalls(embeddingModel, chatModel, modelStarted,
                    true, null, isChatAttempted(chatProvider.get()), false, "STREAM_INCOMPLETE");
            persist(prepared, streamedAnswer.toString(), "FAILED",
                    chatProvider.get(), embeddingProvider.get(),
                    "STREAM_INCOMPLETE", List.of(), false);
            throw error;
        }

        String answer = streamedAnswer.toString().trim();
        if (answer.isEmpty() && stringValue(done.get("answer")).length() > 0) {
            answer = stringValue(done.get("answer")).trim();
        }
        chatProvider.set(firstNonBlank(chatProvider.get(), stringValue(done.get("chat_provider"))));
        embeddingProvider.set(firstNonBlank(
                embeddingProvider.get(), stringValue(done.get("embedding_provider"))));
        boolean isNoAnswer = noAnswer[0] || "no_answer".equals(stringValue(done.get("finish_reason")));
        if (isNoAnswer) {
            recordModelCalls(embeddingModel, chatModel, modelStarted,
                    true, null, false, false, null);
            return persist(prepared, NO_ANSWER, "OK",
                    chatProvider.get(), embeddingProvider.get(), null, List.of(), true);
        }
        if (answer.isEmpty()) {
            BusinessException error = new BusinessException(
                    ErrorCode.EMPTY_COMPLETION, "模型未返回有效内容");
            recordModelCalls(embeddingModel, chatModel, modelStarted,
                    true, null, true, false, "EMPTY_COMPLETION");
            persist(prepared, "", "EMPTY_COMPLETION",
                    chatProvider.get(), embeddingProvider.get(),
                    "EMPTY_COMPLETION", List.of(), false);
            throw error;
        }

        List<Map<String, Object>> payloads = citationPayloads(done.get("citations"));
        List<QaDtos.Citation> citations = citationResolver.resolve(
                payloads, new HashSet<>(prepared.kbIds()), retrievedKeys);
        recordModelCalls(embeddingModel, chatModel, modelStarted,
                true, null, true, true, null);
        return persist(prepared, answer, "OK",
                chatProvider.get(), embeddingProvider.get(), null, citations, false);
    }

    private void handleRetrieval(
            AiStreamEvent event,
            EventSink sink,
            Set<String> retrievedKeys,
            AtomicReference<String> chatProvider,
            AtomicReference<String> embeddingProvider) throws IOException {
        chatProvider.set(stringValue(event.data().get("chat_provider")));
        embeddingProvider.set(stringValue(event.data().get("embedding_provider")));
        List<Map<String, Object>> citations = citationPayloads(event.data().get("citations"));
        for (Map<String, Object> citation : citations) {
            Object docId = citation.get("doc_id");
            Object seq = citation.get("seq");
            if (docId instanceof Number doc && seq instanceof Number sequence) {
                retrievedKeys.add(QaCitationResolver.key(doc.longValue(), sequence.intValue()));
            }
        }
        sink.emit("retrieval", mapOf(
                "count", citations.size(),
                "mode", stringValue(event.data().get("mode")),
                "chatProvider", chatProvider.get(),
                "embeddingProvider", embeddingProvider.get()));
    }

    private GeneratedAnswer persist(
            PreparedAsk prepared,
            String answer,
            String status,
            String chatProvider,
            String embeddingProvider,
            String errorCode,
            List<QaDtos.Citation> citations,
            boolean noAnswer) {
        long latency = Math.max(0, System.currentTimeMillis() - prepared.startedAt());
        int latencyInt = latency > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) latency;
        LocalDateTime now = LocalDateTime.now();
        qaRepository.updateMessage(
                prepared.assistantMessageId(),
                answer == null ? "" : answer,
                status,
                chatProvider == null || chatProvider.isBlank() ? null : chatProvider,
                latencyInt,
                errorCode,
                now);
        if ("OK".equals(status)) {
            for (QaDtos.Citation citation : citations) {
                qaRepository.insertCitation(
                        prepared.assistantMessageId(),
                        citation.chunkId(),
                        citation.docId(),
                        citation.rank(),
                        citation.score(),
                        citation.quote(),
                        now);
            }
        }
        return new GeneratedAnswer(
                answer == null ? "" : answer,
                citations,
                noAnswer,
                chatProvider == null ? "" : chatProvider,
                embeddingProvider == null ? "" : embeddingProvider,
                latencyInt,
                status);
    }

    private void recordModelCalls(
            ModelConfigService.RuntimeSelection embeddingModel,
            ModelConfigService.RuntimeSelection chatModel,
            long startedNanos,
            boolean embeddingSuccess,
            String embeddingError,
            boolean chatInvoked,
            boolean chatSuccess,
            String chatError) {
        if (callLogService == null) {
            return;
        }
        long latency = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        callLogService.record(
                embeddingModel == null ? null : embeddingModel.configId(),
                "EMBED", embeddingSuccess, latency,
                embeddingError, embeddingSuccess ? null : embeddingError);
        if (chatInvoked) {
            callLogService.record(
                    chatModel == null ? null : chatModel.configId(),
                    "CHAT", chatSuccess, latency,
                    chatError, chatSuccess ? null : chatError);
        }
    }

    private static boolean isChatAttempted(String provider) {
        return provider != null && !provider.isBlank() && !"none".equalsIgnoreCase(provider);
    }

    private void ensureUserActive(int userId) {
        UserRepository.UserState state = userRepository.findStateById(userId).orElse(null);
        if (state == null || !"ACTIVE".equals(state.status())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
    }

    private static BusinessException remoteEventError(Map<String, Object> data) {
        String code = stringValue(data.get("code"));
        String message = stringValue(data.get("message"));
        ErrorCode errorCode = "EMPTY_COMPLETION".equals(code)
                ? ErrorCode.EMPTY_COMPLETION
                : ErrorCode.DEPENDENCY_UNAVAILABLE;
        return new BusinessException(
                errorCode,
                message.isEmpty() ? errorCode.defaultMessage() : message);
    }

    private static Map<String, Object> errorPayload(BusinessException error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", error.getErrorCode().code());
        payload.put("message", error.getMessage());
        payload.put("traceId", TraceContext.current());
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> citationPayloads(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private static String validateQuestion(String question) {
        String value = question == null ? "" : question.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "问题不能为空");
        }
        if (value.codePointCount(0, value.length()) > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "问题不能超过 2000 字");
        }
        return value;
    }

    private static String autoTitle(String question) {
        String condensed = question.replaceAll("\\s+", " ").trim();
        int end = condensed.offsetByCodePoints(0, Math.min(60, condensed.codePointCount(0, condensed.length())));
        String title = condensed.substring(0, end);
        return title.isBlank() ? "新对话" : title;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return map;
    }

    @FunctionalInterface
    private interface EventSink {
        EventSink NOOP = (event, payload) -> {
        };

        void emit(String event, Map<String, Object> payload) throws IOException;
    }

    private static final class ClientDisconnectedException extends RuntimeException {
        private ClientDisconnectedException(Throwable cause) {
            super(cause);
        }
    }

    private record PreparedAsk(int userId, long conversationId, String title,
            String question, List<Long> kbIds, List<Map<String, String>> history,
            long userMessageId, long assistantMessageId, long startedAt) {
    }

    private record GeneratedAnswer(String answer, List<QaDtos.Citation> citations,
            boolean noAnswer, String chatProvider, String embeddingProvider,
            long latencyMs, String status) {
    }
}
