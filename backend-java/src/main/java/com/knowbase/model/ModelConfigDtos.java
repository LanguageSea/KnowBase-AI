package com.knowbase.model;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class ModelConfigDtos {

    private ModelConfigDtos() {
    }

    public record ModelItem(
            long id,
            String provider,
            String modelType,
            String modelName,
            String baseUrl,
            String maskedApiKey,
            boolean hasApiKey,
            String keyStatus,
            Map<String, Object> params,
            boolean enabled,
            String lastTestAt,
            Boolean lastTestOk,
            String lastTestError,
            String createdAt,
            String updatedAt) {
    }

    public record ModelListResponse(List<ModelItem> items, long total) {
    }

    public record CreateRequest(
            @NotBlank(message = "provider 不能为空")
            @Size(max = 64, message = "provider 不能超过 64 个字符")
            String provider,
            @NotBlank(message = "modelType 不能为空")
            @Pattern(regexp = "CHAT|EMBEDDING|RERANK", message = "modelType 不合法")
            String modelType,
            @NotBlank(message = "modelName 不能为空")
            @Size(max = 128, message = "modelName 不能超过 128 个字符")
            String modelName,
            @NotBlank(message = "baseUrl 不能为空")
            @Size(max = 512, message = "baseUrl 不能超过 512 个字符")
            String baseUrl,
            @Size(max = 1024, message = "API Key 不能超过 1024 个字符")
            String apiKey,
            Map<String, Object> params,
            Boolean enabled) {
    }

    public record UpdateRequest(
            @NotBlank(message = "provider 不能为空")
            @Size(max = 64, message = "provider 不能超过 64 个字符")
            String provider,
            @NotBlank(message = "modelType 不能为空")
            @Pattern(regexp = "CHAT|EMBEDDING|RERANK", message = "modelType 不合法")
            String modelType,
            @NotBlank(message = "modelName 不能为空")
            @Size(max = 128, message = "modelName 不能超过 128 个字符")
            String modelName,
            @NotBlank(message = "baseUrl 不能为空")
            @Size(max = 512, message = "baseUrl 不能超过 512 个字符")
            String baseUrl,
            @Size(max = 1024, message = "API Key 不能超过 1024 个字符")
            String apiKey,
            boolean clearApiKey,
            Map<String, Object> params,
            Boolean enabled) {
    }

    public record TestResponse(boolean success, long latencyMs, String errorType,
            String message, String testedAt) {
    }
}
