package com.knowbase.kb;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public final class KbMemberDtos {

    private KbMemberDtos() {
    }

    public record MemberItem(int userId, String username, String nickname, String role,
            String createdAt) {
    }

    public record MemberListResponse(List<MemberItem> items, long total) {
    }

    public record GrantRequest(
            @NotNull(message = "userId 不能为空")
            Integer userId,

            @NotBlank(message = "角色不能为空")
            @Pattern(regexp = "VIEWER|EDITOR|MANAGER", message = "成员角色不合法")
            String role) {
    }

    public record RoleRequest(
            @NotBlank(message = "角色不能为空")
            @Pattern(regexp = "VIEWER|EDITOR|MANAGER", message = "成员角色不合法")
            String role) {
    }

    public record UserCandidate(int userId, String username, String nickname) {
    }
}
