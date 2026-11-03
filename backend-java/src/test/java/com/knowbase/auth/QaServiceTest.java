package com.knowbase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.knowbase.ai.PythonClient;
import com.knowbase.ai.PythonClient.AiStreamEvent;
import com.knowbase.config.QaProperties;
import com.knowbase.kb.AuthzService;
import com.knowbase.qa.QaCitationResolver;
import com.knowbase.qa.QaDtos;
import com.knowbase.qa.QaRateLimiter;
import com.knowbase.qa.QaRepository;
import com.knowbase.qa.QaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class QaServiceTest {

    private AuthzService authzService;
    private UserRepository userRepository;
    private QaRepository repository;
    private PythonClient pythonClient;
    private QaCitationResolver citationResolver;
    private QaService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        CurrentUser.set(new AuthUser(7, "alice", "Alice", List.of("USER")));
        authzService = mock(AuthzService.class);
        userRepository = mock(UserRepository.class);
        repository = mock(QaRepository.class);
        pythonClient = mock(PythonClient.class);
        citationResolver = mock(QaCitationResolver.class);

        when(authzService.canReadKb(7, 11L)).thenReturn(true);
        when(citationResolver.resolve(any(), any(), any())).thenReturn(List.of());
        when(userRepository.findStateById(7)).thenReturn(Optional.of(
                new UserRepository.UserState(7, "alice", "Alice", "ACTIVE", 1)));
        when(repository.insertConversation(eq(7), anyString(), any(LocalDateTime.class)))
                .thenReturn(100L);
        when(repository.insertMessage(
                eq(100L), eq("USER"), anyString(), eq("OK"),
                any(), any(), any(), any(LocalDateTime.class))).thenReturn(201L);
        when(repository.insertMessage(
                eq(100L), eq("ASSISTANT"), eq(""), eq("STREAMING"),
                any(), any(), any(), any(LocalDateTime.class))).thenReturn(202L);

        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactionTemplate).execute(any());

        service = new QaService(
                authzService,
                userRepository,
                repository,
                pythonClient,
                mock(QaRateLimiter.class),
                citationResolver,
                transactionTemplate,
                mock(ExecutorService.class),
                new QaProperties(5, 0.2, 20, 3000, 8, 90000L));
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void noAnswerEventPersistsFixedAnswerInsteadOfEmptyCompletion() {
        PythonClient.ChatStream stream = mock(PythonClient.ChatStream.class);
        when(pythonClient.openChatStream(anyMap())).thenReturn(stream);
        doAnswer(invocation -> {
            Consumer<AiStreamEvent> consumer = invocation.getArgument(0);
            consumer.accept(new AiStreamEvent(
                    "no_answer",
                    Map.of(
                            "answer", "未找到相关依据",
                            "chat_provider", "none",
                            "embedding_provider", "local-hash")));
            consumer.accept(new AiStreamEvent(
                    "done",
                    Map.of(
                            "finish_reason", "no_answer",
                            "chat_provider", "none",
                            "embedding_provider", "local-hash",
                            "citations", List.of())));
            return null;
        }).when(stream).consume(any(Consumer.class), any(BooleanSupplier.class));

        QaDtos.AskResponse response = service.ask(
                new QaDtos.AskRequest(11L, null, "完全没有命中资料的问题"));

        assertThat(response.answer()).isEqualTo("未找到相关依据");
        assertThat(response.noAnswer()).isTrue();
        assertThat(response.citations()).isEmpty();
        assertThat(response.chatProvider()).isEqualTo("none");
        verify(repository).updateMessage(
                eq(202L),
                eq("未找到相关依据"),
                eq("OK"),
                eq("none"),
                anyInt(),
                eq(null),
                any(LocalDateTime.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generalFallbackPersistsModelAnswerWithoutCitations() {
        PythonClient.ChatStream stream = mock(PythonClient.ChatStream.class);
        when(pythonClient.openChatStream(anyMap())).thenReturn(stream);
        String answer = "知识库中未找到相关依据。\n\n这是基于通用知识的回答，不代表企业内部制度。";
        doAnswer(invocation -> {
            Consumer<AiStreamEvent> consumer = invocation.getArgument(0);
            consumer.accept(new AiStreamEvent(
                    "retrieval",
                    Map.of(
                            "mode", "general_fallback",
                            "chat_provider", "openai-compatible:deepseek-chat",
                            "embedding_provider", "local-hash-v1",
                            "citations", List.of())));
            consumer.accept(new AiStreamEvent(
                    "delta",
                    Map.of("text", "知识库中未找到相关依据。\n\n")));
            consumer.accept(new AiStreamEvent(
                    "delta",
                    Map.of("text", "这是基于通用知识的回答，不代表企业内部制度。")));
            consumer.accept(new AiStreamEvent(
                    "done",
                    Map.of(
                            "finish_reason", "stop",
                            "mode", "general_fallback",
                            "answer", answer,
                            "chat_provider", "openai-compatible:deepseek-chat",
                            "embedding_provider", "local-hash-v1",
                            "citations", List.of())));
            return null;
        }).when(stream).consume(any(Consumer.class), any(BooleanSupplier.class));

        QaDtos.AskResponse response = service.ask(
                new QaDtos.AskRequest(11L, null, "知识库中没有的通用知识问题"));

        assertThat(response.answer()).isEqualTo(answer);
        assertThat(response.noAnswer()).isFalse();
        assertThat(response.citations()).isEmpty();
        assertThat(response.chatProvider()).isEqualTo("openai-compatible:deepseek-chat");
        verify(repository).updateMessage(
                eq(202L),
                eq(answer),
                eq("OK"),
                eq("openai-compatible:deepseek-chat"),
                anyInt(),
                isNull(),
                any(LocalDateTime.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deactivationBeforeStreamCompletionPersistsCancelled() {
        PythonClient.ChatStream stream = mock(PythonClient.ChatStream.class);
        when(pythonClient.openChatStream(anyMap())).thenReturn(stream);
        doAnswer(invocation -> {
            Consumer<AiStreamEvent> consumer = invocation.getArgument(0);
            consumer.accept(new AiStreamEvent("delta", Map.of("text", "部分回答")));
            consumer.accept(new AiStreamEvent(
                    "done",
                    Map.of(
                            "answer", "部分回答",
                            "chat_provider", "local-extractive",
                            "embedding_provider", "local-hash-v1",
                            "citations", List.of())));
            return null;
        }).when(stream).consume(any(Consumer.class), any(BooleanSupplier.class));
        when(userRepository.findStateById(7)).thenReturn(Optional.of(
                new UserRepository.UserState(7, "alice", "Alice", "DISABLED", 2)));

        assertThatThrownBy(() -> service.ask(
                new QaDtos.AskRequest(11L, null, "流式回答过程中账号被禁用")))
                .isInstanceOf(com.knowbase.common.BusinessException.class);

        verify(repository).updateMessage(
                eq(202L),
                eq("部分回答"),
                eq("CANCELLED"),
                isNull(),
                anyInt(),
                eq("40101"),
                any(LocalDateTime.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void questionWithoutKbIdSearchesAllReadableKnowledgeBases() {
        when(authzService.readableKbIds(7)).thenReturn(List.of(11L, 12L));
        PythonClient.ChatStream stream = mock(PythonClient.ChatStream.class);
        when(pythonClient.openChatStream(anyMap())).thenReturn(stream);
        doAnswer(invocation -> {
            Consumer<AiStreamEvent> consumer = invocation.getArgument(0);
            consumer.accept(new AiStreamEvent(
                    "delta", Map.of("text", "全局回答")));
            consumer.accept(new AiStreamEvent(
                    "done",
                    Map.of(
                            "answer", "全局回答",
                            "chat_provider", "fake",
                            "embedding_provider", "local-hash-v1",
                            "citations", List.of())));
            return null;
        }).when(stream).consume(any(Consumer.class), any(BooleanSupplier.class));

        QaDtos.AskResponse response = service.ask(
                new QaDtos.AskRequest(null, null, "综合所有知识库回答"));

        ArgumentCaptor<Map<String, Object>> requestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(pythonClient).openChatStream(requestCaptor.capture());
        assertThat(requestCaptor.getValue().get("kb_ids")).isEqualTo(List.of(11L, 12L));
        verify(repository).insertConversationKb(
                eq(100L), eq(11L), any(LocalDateTime.class));
        verify(repository).insertConversationKb(
                eq(100L), eq(12L), any(LocalDateTime.class));
        assertThat(response.answer()).isEqualTo("全局回答");
    }
}
