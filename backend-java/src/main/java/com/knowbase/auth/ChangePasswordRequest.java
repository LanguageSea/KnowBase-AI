package com.knowbase.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank(message = "当前密码不能为空")
        @Size(max = 128, message = "当前密码不能超过 128 个字符")
        String currentPassword,

        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 128, message = "新密码长度必须在 8-128 个字符之间")
        String newPassword) {
}
