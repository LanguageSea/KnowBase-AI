package com.knowbase.audit;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;

import com.knowbase.auth.AuthUser;
import com.knowbase.auth.CurrentUser;
import com.knowbase.common.ApiResponse;
import com.knowbase.common.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes one audit row per annotated controller call, including failures.
 * Request arguments are deliberately not serialized: login and model requests
 * contain credentials.
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final OperationLogRepository repository;

    public AuditAspect(OperationLogRepository repository) {
        this.repository = repository;
    }

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        boolean success = false;
        Throwable failure = null;
        Object result = null;
        try {
            result = joinPoint.proceed();
            success = true;
            return result;
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                write(joinPoint, audit, result, success, failure);
            } catch (RuntimeException ex) {
                // Audit is important, but must not replace the real business result.
                log.warn("写操作审计失败 action={} error={}", audit.action(),
                        ex.getClass().getSimpleName());
            }
        }
    }

    private void write(ProceedingJoinPoint joinPoint, Audit audit, Object result,
            boolean success, Throwable failure) {
        AuthUser current = CurrentUser.get();
        Object payload = result instanceof ApiResponse<?> response ? response.data() : result;
        Integer operatorId = current == null ? extractUserId(payload) : current.id();
        String resourceId = extractArgument(joinPoint, audit.resourceIdParam())
                .orElseGet(() -> extractId(payload));
        String detail = failure == null ? null
                : "{\"errorType\":\"" + failure.getClass().getSimpleName() + "\"}";
        HttpServletRequest request = currentRequest();
        repository.insert(
                operatorId,
                audit.action(),
                audit.resourceType(),
                resourceId,
                success,
                detail,
                request == null ? null : request.getRemoteAddr(),
                TraceContext.current(),
                LocalDateTime.now());
    }

    private static Optional<String> extractArgument(
            ProceedingJoinPoint joinPoint, String parameterName) {
        if (parameterName == null || parameterName.isBlank()) {
            return Optional.empty();
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String[] names = signature.getParameterNames();
        Object[] args = joinPoint.getArgs();
        for (int index = 0; index < names.length && index < args.length; index++) {
            if (parameterName.equals(names[index]) && args[index] != null) {
                return Optional.of(String.valueOf(args[index]));
            }
        }
        return Optional.empty();
    }

    private static String extractId(Object payload) {
        if (payload == null) {
            return null;
        }
        Optional<Object> id = invoke(payload, "id");
        return id.map(String::valueOf).orElse(null);
    }

    private static Integer extractUserId(Object payload) {
        if (payload == null) {
            return null;
        }
        Optional<Object> user = invoke(payload, "user");
        if (user.isPresent()) {
            Optional<Object> id = invoke(user.get(), "id");
            if (id.filter(Number.class::isInstance).isPresent()) {
                return ((Number) id.get()).intValue();
            }
        }
        Optional<Object> id = invoke(payload, "id");
        return id.filter(Number.class::isInstance)
                .map(value -> ((Number) value).intValue())
                .orElse(null);
    }

    private static Optional<Object> invoke(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            return Optional.ofNullable(method.invoke(target));
        } catch (ReflectiveOperationException ex) {
            return Optional.empty();
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }
}
