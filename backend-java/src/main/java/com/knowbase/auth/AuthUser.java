package com.knowbase.auth;

import java.util.List;

/**
 * 当前请求的登录用户。
 *
 * <p>字段与 {@code GET /auth/me} 返回的 {@code user} 对象一一对应（docs/API_SPEC.md §1.2），
 * 前端可以直接复用同一套类型。
 *
 * <p>角色是「查库得到的当前值」，不是 token 里带的：角色被撤销后下一个请求就生效，
 * 不需要等 token 过期。
 *
 * @param id       用户主键
 * @param username 登录名
 * @param nickname 显示名
 * @param roles    角色编码列表，如 {@code ["SYS_ADMIN"]}；无角色时为空列表而不是 null
 */
public record AuthUser(int id, String username, String nickname, List<String> roles) {

    public AuthUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
