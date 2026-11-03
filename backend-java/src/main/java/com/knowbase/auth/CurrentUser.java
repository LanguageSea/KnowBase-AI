package com.knowbase.auth;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;

/**
 * 当前请求的登录用户上下文（ThreadLocal）。
 *
 * <p>生命周期严格限定在一次请求内：由 {@link AuthFilter} 在鉴权通过后写入，
 * 并在 {@code finally} 中调用 {@link #clear()}。漏掉 clear 会让线程池复用线程时
 * 把上一个用户的身份带给下一个请求——这是最典型的越权漏洞来源，所以不在别处 set。
 */
public final class CurrentUser {

    private static final ThreadLocal<AuthUser> HOLDER = new ThreadLocal<>();

    private CurrentUser() {
    }

    static void set(AuthUser user) {
        HOLDER.set(user);
    }

    /** 未登录时返回 null；需要「一定登录」的场景用 {@link #require()}。 */
    public static AuthUser get() {
        return HOLDER.get();
    }

    /**
     * 取当前用户，未登录直接 401。
     *
     * <p>受保护路径已经过 {@link AuthFilter}，正常不会走到异常分支；
     * 保留它是为了让 Controller/Service 不写 null 判断，也防止将来改白名单时静默放行。
     */
    public static AuthUser require() {
        AuthUser user = HOLDER.get();
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
        return user;
    }

    /** 必须在请求结束时调用。 */
    static void clear() {
        HOLDER.remove();
    }
}
