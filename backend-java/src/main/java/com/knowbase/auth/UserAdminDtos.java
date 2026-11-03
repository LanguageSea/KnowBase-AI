package com.knowbase.auth;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserAdminDtos {

    private UserAdminDtos() {
    }

    public record UserItem(int id, String username, String nickname, String email,
            String status, List<String> roles, String lastLoginAt, String createdAt) {
    }

    public record UserListResponse(List<UserItem> items, long total) {
    }

    public record CreateRequest(
            @NotBlank(message = "用户名不能为空")
            @Pattern(regexp = "^[A-Za-z0-9_.-]{3,64}$",
                    message = "用户名只能包含字母、数字、点、下划线和短横线，长度 3-64")
            String username,

            @NotBlank(message = "密码不能为空")
            @Size(min = 8, max = 128, message = "密码长度必须在 8-128 个字符之间")
            String password,

            @NotBlank(message = "昵称不能为空")
            @Size(max = 64, message = "昵称不能超过 64 个字符")
            String nickname,

            @Email(message = "邮箱格式不正确")
            @Size(max = 128, message = "邮箱不能超过 128 个字符")
            String email,

            @NotEmpty(message = "至少分配一个角色")
            List<String> roles) {
    }

    public record UpdateRequest(
            @NotBlank(message = "昵称不能为空")
            @Size(max = 64, message = "昵称不能超过 64 个字符")
            String nickname,

            @Email(message = "邮箱格式不正确")
            @Size(max = 128, message = "邮箱不能超过 128 个字符")
            String email) {
    }

    public record StatusRequest(
            @NotBlank(message = "状态不能为空")
            @Pattern(regexp = "ACTIVE|DISABLED", message = "状态只能是 ACTIVE 或 DISABLED")
            String status) {
    }

    public record RolesRequest(
            @NotEmpty(message = "至少分配一个角色")
            List<String> roles) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "新密码不能为空")
            @Size(min = 8, max = 128, message = "新密码长度必须在 8-128 个字符之间")
            String newPassword) {
    }
}
