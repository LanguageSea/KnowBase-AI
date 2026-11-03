package com.knowbase.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置。
 *
 * <p>只放有效期。签名密钥刻意不做配置项：它由 {@code APP_MASTER_KEY} 派生，
 * 避免再多一处需要备份、可能被写进仓库或日志的明文密钥（docs/API_SPEC.md §1.1）。
 *
 * <p>用包装类型 {@link Integer} 而不是 {@code int}：配置缺失时能区分「没配」与「配成 0」，
 * 两种情况都由 {@link JwtService} 在启动期拒绝，而不是悄悄签出一个永不过期的 token。
 *
 * @param ttlSeconds 访问令牌有效期（秒），公共配置 {@code app.jwt.ttl-seconds}
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(Integer ttlSeconds) {
}
