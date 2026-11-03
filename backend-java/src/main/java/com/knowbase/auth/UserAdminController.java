package com.knowbase.auth;

import com.knowbase.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统用户管理。所有方法必须有 SYS_ADMIN；普通用户由 PermissionAspect 统一返回 403。
 */
@RestController
@RequestMapping("/admin/users")
public class UserAdminController {

    private final UserAdminService userAdminService;

    public UserAdminController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping
    @RequirePermission(Permission.USER_MANAGE)
    public ApiResponse<UserAdminDtos.UserListResponse> list() {
        return ApiResponse.ok(userAdminService.list());
    }

    @GetMapping("/{userId}")
    @RequirePermission(Permission.USER_MANAGE)
    public ApiResponse<UserAdminDtos.UserItem> get(@PathVariable int userId) {
        return ApiResponse.ok(userAdminService.get(userId));
    }

    @PostMapping
    @RequirePermission(Permission.USER_MANAGE)
    public ApiResponse<UserAdminDtos.UserItem> create(
            @Valid @RequestBody UserAdminDtos.CreateRequest request) {
        return ApiResponse.ok(userAdminService.create(request));
    }

    @PutMapping("/{userId}")
    @RequirePermission(Permission.USER_MANAGE)
    public ApiResponse<UserAdminDtos.UserItem> update(
            @PathVariable int userId,
            @Valid @RequestBody UserAdminDtos.UpdateRequest request) {
        return ApiResponse.ok(userAdminService.update(userId, request));
    }

    @PatchMapping("/{userId}/status")
    @RequirePermission(Permission.USER_MANAGE)
    public ApiResponse<UserAdminDtos.UserItem> updateStatus(
            @PathVariable int userId,
            @Valid @RequestBody UserAdminDtos.StatusRequest request) {
        return ApiResponse.ok(userAdminService.updateStatus(userId, request));
    }

    @PutMapping("/{userId}/roles")
    @RequirePermission(Permission.USER_MANAGE)
    public ApiResponse<UserAdminDtos.UserItem> replaceRoles(
            @PathVariable int userId,
            @Valid @RequestBody UserAdminDtos.RolesRequest request) {
        return ApiResponse.ok(userAdminService.replaceRoles(userId, request));
    }

    @PutMapping("/{userId}/password")
    @RequirePermission(Permission.USER_MANAGE)
    public ApiResponse<Void> resetPassword(
            @PathVariable int userId,
            @Valid @RequestBody UserAdminDtos.ResetPasswordRequest request) {
        userAdminService.resetPassword(userId, request);
        return ApiResponse.ok(null);
    }

    /** DELETE 语义是禁用，不物理删除，保留审计主体。 */
    @DeleteMapping("/{userId}")
    @RequirePermission(Permission.USER_MANAGE)
    public ApiResponse<Void> disable(@PathVariable int userId) {
        userAdminService.disable(userId);
        return ApiResponse.ok(null);
    }
}
