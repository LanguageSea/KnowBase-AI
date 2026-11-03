package com.knowbase.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Date;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * JWT 签发与校验。
 *
 * <p>算法固定 HS256，载荷固定五项：
 * <ul>
 *   <li>{@code sub} —— 用户名（便于排查日志，不作为身份依据）；</li>
 *   <li>{@code uid} —— 用户主键，**唯一可信的身份来源**；</li>
 *   <li>{@code ver} —— 签发时的 {@code sys_user.token_version}，与库里不一致即失效（D9）；</li>
 *   <li>{@code iat} / {@code exp} —— 签发与过期时间。</li>
 * </ul>
 *
 * <p>签名密钥不新增配置项，直接派生：
 * {@code HMAC-SHA256(APP_MASTER_KEY 的 UTF-8 字节, "knowbase-jwt-v1")} → 32 字节 → {@link SecretKeySpec}。
 * 标签里带 {@code -v1} 是为将来轮换算法留出口：换标签即等于换一整套密钥，
 * 旧 token 全部失效，无需黑名单。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** 派生标签，改动即视为密钥轮换。 */
    private static final String DERIVATION_LABEL = "knowbase-jwt-v1";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** 业务上 token 无效时的统一文案：不区分「签名错」「过期」「用户被禁用」，避免给攻击者信息。 */
    private static final String INVALID_TOKEN_MESSAGE = "登录状态已失效，请重新登录";

    private final SecretKey signingKey;
    private final long ttlSeconds;

    public JwtService(JwtProperties jwtProperties, SecurityProperties securityProperties) {
        String masterKey = securityProperties.masterKey();
        if (masterKey == null || masterKey.isBlank()) {
            // 正常路径下 StartupSecurityValidator 已经拦过；这里兜底，避免用空密钥签出可伪造 token。
            throw new IllegalStateException("APP_MASTER_KEY 未设置，无法派生 JWT 签名密钥");
        }
        Integer configuredTtl = jwtProperties.ttlSeconds();
        if (configuredTtl == null || configuredTtl <= 0) {
            throw new IllegalStateException("app.jwt.ttl-seconds 必须为正整数（当前：" + configuredTtl + "）");
        }
        this.signingKey = new SecretKeySpec(deriveKey(masterKey.trim()), HMAC_ALGORITHM);
        this.ttlSeconds = configuredTtl;
        log.info("JWT 已启用：HS256，有效期 {} 秒", ttlSeconds);
    }

    /** 令牌有效期（秒），登录响应里的 {@code expiresInSeconds} 直接取它。 */
    public long ttlSeconds() {
        return ttlSeconds;
    }

    /**
     * 签发访问令牌。
     *
     * @param userId       用户主键
     * @param username     用户名，仅用于排查
     * @param tokenVersion 当前 {@code sys_user.token_version}
     */
    public String issue(int userId, String username, int tokenVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("uid", userId)
                .claim("ver", tokenVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * 解析并校验签名与有效期。
     *
     * <p>只做「密码学上是否可信」，不做授权：用户是否仍存在、是否被禁用、
     * token_version 是否一致由 {@link AuthService} 查库判断。
     *
     * @throws BusinessException 401/40101，令牌缺失、篡改、过期或载荷不完整
     */
    public ParsedToken parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, INVALID_TOKEN_MESSAGE);
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            Integer uid = claims.get("uid", Integer.class);
            Integer version = claims.get("ver", Integer.class);
            String username = claims.getSubject();
            if (uid == null || version == null || username == null || username.isBlank()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, INVALID_TOKEN_MESSAGE);
            }
            return new ParsedToken(uid, username, version);
        } catch (JwtException | IllegalArgumentException ex) {
            // 只记录异常类型，不记录 token 内容：token 等同于会话凭证。
            log.debug("JWT 校验失败：{}", ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.UNAUTHORIZED, INVALID_TOKEN_MESSAGE);
        }
    }

    /** 由主密钥派生 32 字节签名密钥。 */
    private static byte[] deriveKey(String masterKey) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(masterKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(DERIVATION_LABEL.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("当前 JVM 不支持 " + HMAC_ALGORITHM + "，无法生成 JWT 签名密钥", ex);
        }
    }

    /**
     * 通过签名校验的令牌载荷。
     *
     * @param userId       用户主键（{@code uid}）
     * @param username     用户名（{@code sub}）
     * @param tokenVersion 签发时的 {@code token_version}（{@code ver}）
     */
    public record ParsedToken(int userId, String username, int tokenVersion) {
    }
}
