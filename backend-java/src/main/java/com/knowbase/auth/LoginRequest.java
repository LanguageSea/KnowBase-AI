package com.knowbase.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求体（docs/API_SPEC.md §1.1）。
 *
 * <p>两个字段都只做「非空 + 长度上限」：
 * 不做密码复杂度、不允许为空串。长度上限与 {@code sys_user} 列宽对齐，
 * 避免超长输入把 SQL 打成 1406 错误，也避免有人拿它当注入探测口。
 *
 * @param username 登录名
 * @param password 明文口令，仅在本次请求内存在，不落日志、不进审计
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名长度不能超过 64 个字符")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(max = 128, message = "密码长度不能超过 128 个字符")
        String password) {
}
