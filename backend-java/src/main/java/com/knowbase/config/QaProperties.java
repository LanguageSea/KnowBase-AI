package com.knowbase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 问答检索、上下文、限流与流式输出参数。
 *
 * <p>topK 与 scoreThreshold 均可配置；缺失或非法时由 QaService 启动期拒绝，
 * 不用静默默认值掩盖配置错误。
 */
@ConfigurationProperties(prefix = "knowbase.qa")
public record QaProperties(
        Integer topK,
        Double scoreThreshold,
        Integer rateLimitPerMinute,
        Integer contextTokenBudget,
        Integer maxContextChunks,
        Long streamTimeoutMs) {
}
