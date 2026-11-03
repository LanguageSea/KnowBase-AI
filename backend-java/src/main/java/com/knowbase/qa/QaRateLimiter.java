package com.knowbase.qa;

import java.time.Duration;
import java.time.Instant;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.config.QaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Per-user, per-minute question counter. The key expires automatically so a
 * missed decrement can never create a permanent lock.
 */
@Service
public class QaRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(QaRateLimiter.class);
    private static final String KEY_PREFIX = "knowbase:qa:rate:";
    private static final Duration KEY_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;
    private final int limit;

    public QaRateLimiter(StringRedisTemplate redis, QaProperties properties) {
        this.redis = redis;
        Integer configured = properties.rateLimitPerMinute();
        this.limit = configured == null ? 20 : configured;
        if (limit < 1) {
            throw new IllegalStateException("knowbase.qa.rate-limit-per-minute 必须为正整数");
        }
    }

    public void check(int userId) {
        long minute = Instant.now().getEpochSecond() / 60;
        String key = KEY_PREFIX + userId + ":" + minute;
        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, KEY_TTL);
            }
            if (count != null && count > limit) {
                throw new BusinessException(
                        ErrorCode.QA_RATE_LIMITED,
                        "提问过于频繁，每分钟最多 " + limit + " 次");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (DataAccessException ex) {
            log.warn("问答限流计数失败 userId={} error={}", userId, ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "问答限流服务暂不可用");
        }
    }
}
