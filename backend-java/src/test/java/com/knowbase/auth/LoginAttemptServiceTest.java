package com.knowbase.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class LoginAttemptServiceTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private LoginAttemptService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new LoginAttemptService(redis);
    }

    @Test
    void lockAfterFiveFailuresAndExpireFifteenMinutes() {
        when(values.increment(anyString())).thenReturn(1L, 2L, 3L, 4L, 5L);

        for (int i = 0; i < 4; i++) {
            service.recordFailure("Admin");
        }
        assertThatThrownBy(() -> service.recordFailure("Admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_LOCKED);

        verify(redis, org.mockito.Mockito.atLeast(2))
                .expire(anyString(), org.mockito.ArgumentMatchers.eq(LoginAttemptService.LOCK_DURATION));
    }

    @Test
    void lockedCounterRejectsBeforePasswordCheck() {
        when(values.get("knowbase:login:fail:admin")).thenReturn("5");

        assertThatThrownBy(() -> service.assertNotLocked(" admin "))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.LOGIN_LOCKED);
    }

    @Test
    void successfulLoginClearsCounter() {
        service.clearFailures("admin");
        verify(redis).delete("knowbase:login:fail:admin");
    }
}
