package com.knowbase.qa;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.config.QaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class QaRateLimiterTest {

    private ValueOperations<String, String> values;
    private QaRateLimiter limiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        limiter = new QaRateLimiter(
                redis,
                new QaProperties(5, 0.2, 2, 3000, 8, 90000L));
    }

    @Test
    void thirdQuestionInTheSameMinuteIsRejected() {
        when(values.increment(anyString())).thenReturn(1L, 2L, 3L);

        limiter.check(7);
        limiter.check(7);

        assertThatThrownBy(() -> limiter.check(7))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.QA_RATE_LIMITED);
    }
}
