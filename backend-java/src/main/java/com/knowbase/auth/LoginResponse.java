package com.knowbase.auth;

/**
 * 登录成功响应体（docs/API_SPEC.md §1.1）。
 *
 * @param token            访问令牌
 * @param tokenType        固定 {@code Bearer}，前端拼 {@code Authorization} 头时直接用它
 * @param expiresInSeconds 有效期（秒），取自 {@code app.jwt.ttl-seconds}
 * @param user             用户信息，与 {@code GET /auth/me} 的 data 完全一致
 */
public record LoginResponse(String token, String tokenType, long expiresInSeconds, AuthUser user) {

    public static LoginResponse of(String token, long expiresInSeconds, AuthUser user) {
        return new LoginResponse(token, "Bearer", expiresInSeconds, user);
    }
}
