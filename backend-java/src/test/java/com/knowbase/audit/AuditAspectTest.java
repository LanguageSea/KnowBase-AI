package com.knowbase.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.knowbase.auth.AuthUser;
import com.knowbase.auth.CurrentUserTestSupport;
import com.knowbase.common.ApiResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AuditAspectTest {

    @AfterEach
    void clear() {
        CurrentUserTestSupport.clear();
    }

    @Test
    void successfulModelUpdateWritesOperatorAndResourceId() throws Throwable {
        CurrentUserTestSupport.authenticate(
                new AuthUser(1, "admin", "管理员", List.of("SYS_ADMIN")));
        OperationLogRepository repository = mock(OperationLogRepository.class);
        AuditAspect aspect = new AuditAspect(repository);
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getParameterNames()).thenReturn(new String[]{"modelId"});
        when(joinPoint.getArgs()).thenReturn(new Object[]{9L});
        when(joinPoint.proceed()).thenReturn(ApiResponse.ok(null));

        aspect.around(joinPoint, audit("MODEL_UPDATE", "MODEL", "modelId"));

        verify(repository).insert(
                eq(1), eq("MODEL_UPDATE"), eq("MODEL"), eq("9"), eq(true),
                isNull(), isNull(), anyString(), any());
    }

    private static Audit audit(String action, String resourceType, String parameter) {
        return new Audit() {
            @Override
            public String action() {
                return action;
            }

            @Override
            public String resourceType() {
                return resourceType;
            }

            @Override
            public String resourceIdParam() {
                return parameter;
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return Audit.class;
            }
        };
    }
}
