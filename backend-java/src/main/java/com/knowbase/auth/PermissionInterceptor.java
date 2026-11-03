package com.knowbase.auth;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 读取 Controller 方法上的 {@link RequirePermission}。401 仍由 AuthFilter 负责，
 * 这里只把“已登录但角色不足”统一转换为 403/40301。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequirePermission required = method.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            Class<?> targetType = AopUtils.getTargetClass(method.getBean());
            required = targetType.getAnnotation(RequirePermission.class);
        }
        if (required == null) {
            return true;
        }
        AuthUser user = CurrentUser.require();
        boolean allowed = switch (required.value()) {
            case USER_MANAGE, MODEL_MANAGE, SYSTEM_MONITOR -> user.roles().contains("SYS_ADMIN");
        };
        if (!allowed) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权执行该管理操作");
        }
        return true;
    }
}
