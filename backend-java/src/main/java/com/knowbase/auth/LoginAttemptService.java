package com.knowbase.auth;

import java.time.Duration;
import java.util.Locale;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis 登录失败计数。键按规范化用户名而不是 IP 计算，避免换 IP 绕过；
 * 5 次失败后锁定 15 分钟，成功登录立即清零。
 */
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private static final String KEY_PREFIX = "knowbase:login:fail:";

    private final StringRedisTemplate redis;

    public LoginAttemptService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void assertNotLocked(String username) {
        try {
            String value = redis.opsForValue().get(key(username));
            if (value != null && Integer.parseInt(value) >= MAX_FAILURES) {
                throw locked();
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (DataAccessException | NumberFormatException ex) {
            log.warn("登录锁定状态读取失败 error={}", ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "登录保护服务暂不可用");
        }
    }

    /**
     * 记录一次失败。第 5 次写入后立刻返回锁定异常，后续请求由 assertNotLocked 拦截。
     */
    public void recordFailure(String username) {
        try {
            String key = key(username);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, LOCK_DURATION);
            }
            if (count != null && count >= MAX_FAILURES) {
                redis.expire(key, LOCK_DURATION);
                throw locked();
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            log.warn("登录失败计数写入失败 error={}", ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "登录保护服务暂不可用");
        }
    }

    public void clearFailures(String username) {
        try {
            redis.delete(key(username));
        } catch (DataAccessException ex) {
            // 账号密码已验证成功。这里不能把成功登录变成失败；残留计数会在 15 分钟后自然过期。
            log.warn("登录失败计数清理失败 usernameHash={}", Integer.toHexString(username.hashCode()));
        }
    }

    private static BusinessException locked() {
        return new BusinessException(ErrorCode.LOGIN_LOCKED, "登录失败次数过多，请 15 分钟后再试");
    }

    private static String key(String username) {
        return KEY_PREFIX + username.trim().toLowerCase(Locale.ROOT);
    }
}
