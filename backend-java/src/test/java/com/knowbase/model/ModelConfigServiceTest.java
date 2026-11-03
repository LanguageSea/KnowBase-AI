package com.knowbase.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.knowbase.ai.PythonClient;
import com.knowbase.common.BusinessException;
import com.knowbase.config.SecurityProperties;
import com.knowbase.model.ModelConfigRepository.ModelConfigRow;
import com.knowbase.security.SecretCipher;
import com.knowbase.security.SsrfGuard;
import org.junit.jupiter.api.Test;

class ModelConfigServiceTest {

    private static final String MASTER_KEY =
            Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void createStoresCiphertextAndOnlyReturnsMaskedKey() {
        ModelConfigRepository repository = mock(ModelConfigRepository.class);
        PythonClient pythonClient = mock(PythonClient.class);
        AiCallLogService callLogs = mock(AiCallLogService.class);
        SecretCipher cipher = new SecretCipher(
                new SecurityProperties(MASTER_KEY, "internal-token", false));
        SsrfGuard guard = new SsrfGuard(
                new SecurityProperties(MASTER_KEY, "internal-token", false));
        AtomicReference<String> storedCipher = new AtomicReference<>();
        when(repository.insert(anyString(), any(), anyString(), anyString(), anyString(),
                any(), anyBoolean(), any(LocalDateTime.class))).thenAnswer(invocation -> {
                    storedCipher.set(invocation.getArgument(4));
                    return 42L;
                });
        when(repository.findById(42L)).thenAnswer(invocation -> Optional.of(row(storedCipher.get())));

        ModelConfigService service = new ModelConfigService(
                repository, cipher, guard, pythonClient, callLogs);
        ModelConfigDtos.ModelItem item = service.create(new ModelConfigDtos.CreateRequest(
                "deepseek", "CHAT", "deepseek-chat", "https://8.8.8.8/v1",
                "sk-live-1234567890", Map.of("temperature", 0.2), true));

        assertThat(storedCipher.get()).startsWith("v1:").doesNotContain("sk-live-1234567890");
        assertThat(item.maskedApiKey()).isEqualTo("sk-****7890");
        assertThat(item.hasApiKey()).isTrue();
        assertThat(item.keyStatus()).isEqualTo("OK");
        assertThat(item.toString()).doesNotContain("sk-live");
        verify(repository).insert(anyString(), any(), anyString(), anyString(), anyString(),
                any(), anyBoolean(), any(LocalDateTime.class));
    }

    @Test
    void rejectsPrivateModelEndpointBeforeWriting() {
        ModelConfigService service = new ModelConfigService(
                mock(ModelConfigRepository.class),
                new SecretCipher(new SecurityProperties(MASTER_KEY, "internal-token", false)),
                new SsrfGuard(new SecurityProperties(MASTER_KEY, "internal-token", false)),
                mock(PythonClient.class),
                mock(AiCallLogService.class));

        assertThatThrownBy(() -> service.create(new ModelConfigDtos.CreateRequest(
                "local", "CHAT", "qwen", "https://127.0.0.1:8000/v1",
                "key", Map.of(), true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("禁止访问");
    }

    @Test
    void acceptsStandardTokenLimitParamsButStillRejectsCredentialsInParams() {
        ModelConfigRepository repository = mock(ModelConfigRepository.class);
        SecretCipher cipher = new SecretCipher(
                new SecurityProperties(MASTER_KEY, "internal-token", false));
        SsrfGuard guard = new SsrfGuard(
                new SecurityProperties(MASTER_KEY, "internal-token", false));
        AtomicReference<String> storedCipher = new AtomicReference<>();
        when(repository.insert(anyString(), any(), anyString(), anyString(), anyString(),
                any(), anyBoolean(), any(LocalDateTime.class))).thenAnswer(invocation -> {
                    storedCipher.set(invocation.getArgument(4));
                    return 43L;
                });
        when(repository.findById(43L)).thenAnswer(invocation -> Optional.of(new ModelConfigRow(
                43L, "deepseek", "CHAT", "deepseek-chat", "https://8.8.8.8/v1",
                storedCipher.get(), "{\"max_tokens\":2048,\"temperature\":0.2}",
                true, null, null, null,
                "2026-10-31T00:00:00", "2026-10-31T00:00:00")));

        ModelConfigService service = new ModelConfigService(
                repository, cipher, guard, mock(PythonClient.class),
                mock(AiCallLogService.class));

        assertThat(service.create(new ModelConfigDtos.CreateRequest(
                "deepseek", "CHAT", "deepseek-chat", "https://8.8.8.8/v1",
                "sk-live-1234567890",
                Map.of("max_tokens", 2048, "temperature", 0.2), true)).id())
                .isEqualTo(43L);

        assertThatThrownBy(() -> service.create(new ModelConfigDtos.CreateRequest(
                "deepseek", "CHAT", "deepseek-chat-2", "https://8.8.8.8/v1",
                "sk-live-1234567890", Map.of("api_token", "secret"), true)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("敏感字段");
    }

    private static ModelConfigRow row(String cipher) {
        return new ModelConfigRow(
                42L, "deepseek", "CHAT", "deepseek-chat", "https://8.8.8.8/v1",
                cipher, "{\"temperature\":0.2}", true, null, null, null,
                "2026-10-31T00:00:00", "2026-10-31T00:00:00");
    }
}
