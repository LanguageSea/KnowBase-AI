package com.knowbase.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "knowbase.python")
public record AiProperties(String baseUrl, Integer connectTimeoutMs, Integer readTimeoutMs) {
}
