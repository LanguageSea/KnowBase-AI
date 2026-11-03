package com.knowbase.model;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowbase.ai.PythonClient;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.model.ModelConfigRepository.ModelConfigRow;
import com.knowbase.security.ModelEndpointException;
import com.knowbase.security.SecretCipher;
import com.knowbase.security.SsrfGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelConfigService {

    private static final Logger log = LoggerFactory.getLogger(ModelConfigService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> SENSITIVE_PARAM_MARKERS = Set.of(
            "apikey", "api_key", "token", "secret", "password");
    private static final Set<String> SAFE_MODEL_PARAMETERS = Set.of(
            "max_tokens", "max_token", "max_completion_tokens",
            "max_output_tokens", "prompt_tokens", "completion_tokens",
            "input_tokens", "output_tokens", "total_tokens");

    private final ModelConfigRepository repository;
    private final SecretCipher secretCipher;
    private final SsrfGuard ssrfGuard;
    private final PythonClient pythonClient;
    private final AiCallLogService callLogService;

    public ModelConfigService(ModelConfigRepository repository, SecretCipher secretCipher,
            SsrfGuard ssrfGuard, PythonClient pythonClient, AiCallLogService callLogService) {
        this.repository = repository;
        this.secretCipher = secretCipher;
        this.ssrfGuard = ssrfGuard;
        this.pythonClient = pythonClient;
        this.callLogService = callLogService;
    }

    public ModelConfigDtos.ModelListResponse list() {
        List<ModelConfigDtos.ModelItem> items = repository.findAll().stream()
                .map(this::toItem)
                .toList();
        return new ModelConfigDtos.ModelListResponse(items, items.size());
    }

    public ModelConfigDtos.ModelItem get(long id) {
        return toItem(require(id));
    }

    @Transactional
    public ModelConfigDtos.ModelItem create(ModelConfigDtos.CreateRequest request) {
        ValidatedModel model = validate(
                request.provider(), request.modelType(), request.modelName(), request.baseUrl(),
                request.params(), normalizeSecret(request.apiKey()));
        LocalDateTime now = LocalDateTime.now();
        try {
            long id = repository.insert(model.provider(), model.type(), model.modelName(),
                    model.baseUrl(), secretCipher.encrypt(model.apiKey()), model.paramsJson(),
                    request.enabled() == null || request.enabled(), now);
            return toItem(require(id));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一 provider、模型名称和类型已存在");
        }
    }

    @Transactional
    public ModelConfigDtos.ModelItem update(long id, ModelConfigDtos.UpdateRequest request) {
        ModelConfigRow existing = require(id);
        String apiKey;
        if (request.clearApiKey()) {
            apiKey = null;
        } else if (request.apiKey() != null) {
            apiKey = normalizeSecret(request.apiKey());
        } else {
            apiKey = decryptQuietly(existing);
        }
        ValidatedModel model = validate(
                request.provider(), request.modelType(), request.modelName(), request.baseUrl(),
                request.params(), apiKey);
        boolean enabled = request.enabled() == null ? existing.enabled() : request.enabled();
        try {
            int changed = repository.update(id, model.provider(), model.type(), model.modelName(),
                    model.baseUrl(), secretCipher.encrypt(model.apiKey()), model.paramsJson(),
                    enabled,
                    LocalDateTime.now());
            if (changed != 1) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在");
            }
            return toItem(require(id));
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "同一 provider、模型名称和类型已存在");
        }
    }

    @Transactional
    public void delete(long id) {
        if (repository.delete(id) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在");
        }
    }

    /**
     * Resolves the latest enabled configuration for a capability. Environment
     * variables remain the fallback when no database configuration exists.
     */
    public Optional<RuntimeSelection> runtimeFor(ModelType type) {
        Optional<ModelConfigRow> found = repository.findEnabled(type);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ModelConfigRow row = found.get();
        String apiKey = decrypt(row);
        URI baseUri;
        try {
            baseUri = ssrfGuard.validate(row.baseUrl());
        } catch (ModelEndpointException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                    "模型地址校验失败：" + ex.getMessage());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("config_id", row.id());
        payload.put("provider", row.provider());
        payload.put("model_type", row.modelType());
        payload.put("model_name", row.modelName());
        payload.put("base_url", baseUri.toString());
        payload.put("api_key", apiKey);
        payload.put("params", parseParams(row.params()));
        return Optional.of(new RuntimeSelection(row.id(), payload));
    }

    public ModelConfigDtos.TestResponse test(long id) {
        ModelConfigRow row = require(id);
        long started = System.nanoTime();
        ModelConfigDtos.TestResponse response;
        try {
            URI baseUri = ssrfGuard.validate(row.baseUrl());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("config_id", row.id());
            payload.put("provider", row.provider());
            payload.put("model_type", row.modelType());
            payload.put("model_name", row.modelName());
            payload.put("base_url", baseUri.toString());
            payload.put("api_key", decrypt(row));
            payload.put("params", parseParams(row.params()));
            Map<String, Object> result = pythonClient.testModel(payload);
            boolean success = Boolean.TRUE.equals(result.get("success"));
            String errorType = stringValue(result.get("error_type"));
            String message = stringValue(result.get("message"));
            long latency = elapsedMs(started);
            response = new ModelConfigDtos.TestResponse(
                    success, latency, errorType, message, Instant.now().toString());
            repository.updateTestResult(id, success,
                    success ? null : firstNonBlank(message, errorType), LocalDateTime.now());
            callLogService.record(id, "TEST", success, latency, errorType, message);
        } catch (ModelEndpointException ex) {
            long latency = elapsedMs(started);
            response = failedTest(ex.code(), ex.getMessage(), latency);
            repository.updateTestResult(id, false, ex.getMessage(), LocalDateTime.now());
            callLogService.record(id, "TEST", false, latency, ex.code(), ex.getMessage());
        } catch (BusinessException ex) {
            long latency = elapsedMs(started);
            response = failedTest("DEPENDENCY_UNAVAILABLE", ex.getMessage(), latency);
            repository.updateTestResult(id, false, ex.getMessage(), LocalDateTime.now());
            callLogService.record(id, "TEST", false, latency, "DEPENDENCY_UNAVAILABLE", ex.getMessage());
        } catch (RuntimeException ex) {
            long latency = elapsedMs(started);
            log.warn("模型连接测试异常 modelId={} error={}", id, ex.getClass().getSimpleName());
            response = failedTest("UNKNOWN_ERROR", "模型连接测试失败", latency);
            repository.updateTestResult(id, false, response.message(), LocalDateTime.now());
            callLogService.record(id, "TEST", false, latency, response.errorType(), response.message());
        }
        return response;
    }

    private ModelConfigDtos.TestResponse failedTest(String errorType, String message, long latency) {
        return new ModelConfigDtos.TestResponse(
                false, latency, errorType, message, Instant.now().toString());
    }

    private ModelConfigDtos.ModelItem toItem(ModelConfigRow row) {
        String decrypted;
        String keyStatus;
        try {
            decrypted = decrypt(row);
            keyStatus = decrypted == null ? "EMPTY" : "OK";
        } catch (RuntimeException ex) {
            decrypted = null;
            keyStatus = "UNREADABLE";
        }
        return new ModelConfigDtos.ModelItem(
                row.id(), row.provider(), row.modelType(), row.modelName(), row.baseUrl(),
                SecretCipher.mask(decrypted), decrypted != null, keyStatus,
                parseParams(row.params()), row.enabled(), row.lastTestAt(), row.lastTestOk(),
                row.lastTestError(), row.createdAt(), row.updatedAt());
    }

    private ValidatedModel validate(String provider, String modelType, String modelName,
            String baseUrl, Map<String, Object> params, String apiKey) {
        ModelType type = ModelType.parse(modelType);
        String normalizedProvider = provider.trim().toLowerCase(Locale.ROOT);
        if (!normalizedProvider.matches("[a-z0-9_.-]{1,64}")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "provider 只能包含小写字母、数字、点、下划线和短横线");
        }
        String normalizedName = modelName.trim();
        if (normalizedName.isEmpty() || normalizedName.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "modelName 长度必须在 1-128 之间");
        }
        URI baseUri;
        try {
            baseUri = ssrfGuard.validate(baseUrl);
        } catch (ModelEndpointException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, ex.getMessage());
        }
        Map<String, Object> safeParams = params == null ? Map.of() : params;
        validateParams(safeParams);
        try {
            return new ValidatedModel(type, normalizedProvider, normalizedName,
                    baseUri.toString(), apiKey, safeParams.isEmpty() ? null : JSON.writeValueAsString(safeParams),
                    safeParams);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "params 必须是合法 JSON 对象");
        }
    }

    private void validateParams(Map<String, Object> params) {
        for (String key : params.keySet()) {
            String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT).replace("-", "_");
            if (SAFE_MODEL_PARAMETERS.contains(normalized)) {
                continue;
            }
            for (String marker : SENSITIVE_PARAM_MARKERS) {
                if (normalized.contains(marker)) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                            "敏感字段必须使用 apiKey 保存，不能写入 params");
                }
            }
        }
    }

    private Map<String, Object> parseParams(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, MAP_TYPE);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型参数数据损坏");
        }
    }

    private String decrypt(ModelConfigRow row) {
        try {
            return row.apiKeyCipher() == null ? null : secretCipher.decrypt(row.apiKeyCipher());
        } catch (RuntimeException ex) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, ex.getMessage());
        }
    }

    private String decryptQuietly(ModelConfigRow row) {
        if (row.apiKeyCipher() == null) {
            return null;
        }
        return decrypt(row);
    }

    private ModelConfigRow require(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在"));
    }

    private static String normalizeSecret(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static long elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private record ValidatedModel(ModelType type, String provider, String modelName,
            String baseUrl, String apiKey, String paramsJson, Map<String, Object> params) {
    }

    public record RuntimeSelection(long configId, Map<String, Object> payload) {
        public RuntimeSelection {
            payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }

        @Override
        public String toString() {
            return "RuntimeSelection[configId=" + configId + "]";
        }
    }
}
