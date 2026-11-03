package com.knowbase.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

class PermissionInterceptorTest {

    private final PermissionInterceptor interceptor = new PermissionInterceptor();

    @AfterEach
    void clear() {
        CurrentUser.clear();
    }

    @Test
    void ordinaryUserGets403() throws Exception {
        CurrentUser.set(new AuthUser(2, "user", "普通用户", List.of("USER")));
        HandlerMethod handler = mock(HandlerMethod.class);
        when(handler.getMethodAnnotation(RequirePermission.class))
                .thenReturn(annotation(Permission.USER_MANAGE));

        assertThatThrownBy(() -> interceptor.preHandle(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), handler))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    void sysAdminPasses() {
        CurrentUser.set(new AuthUser(1, "admin", "管理员", List.of("SYS_ADMIN")));
        HandlerMethod handler = mock(HandlerMethod.class);
        when(handler.getMethodAnnotation(RequirePermission.class))
                .thenReturn(annotation(Permission.USER_MANAGE));

        org.assertj.core.api.Assertions.assertThat(
                interceptor.preHandle(mock(HttpServletRequest.class), mock(HttpServletResponse.class), handler))
                .isTrue();
    }

    @Test
    void classLevelPermissionAlsoBlocksOrdinaryUser() throws Exception {
        CurrentUser.set(new AuthUser(2, "user", "普通用户", List.of("USER")));
        HandlerMethod handler = mock(HandlerMethod.class);
        when(handler.getMethodAnnotation(RequirePermission.class)).thenReturn(null);
        when(handler.getBean()).thenReturn(new AdminOnlyController());

        assertThatThrownBy(() -> interceptor.preHandle(
                mock(HttpServletRequest.class), mock(HttpServletResponse.class), handler))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    private static RequirePermission annotation(Permission permission) {
        return new RequirePermission() {
            @Override
            public Permission value() {
                return permission;
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RequirePermission.class;
            }
        };
    }

    @RequirePermission(Permission.MODEL_MANAGE)
    private static final class AdminOnlyController {
        @SuppressWarnings("unused")
        public void action() {
        }
    }
}
