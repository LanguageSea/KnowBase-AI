package com.knowbase.auth;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.knowbase.auth.UserRepository.ManagedUser;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserAdminService {

    private static final Set<String> ALLOWED_ROLES = Set.of("SYS_ADMIN", "KB_ADMIN", "USER");
    private static final Set<String> ALLOWED_STATUSES = Set.of("ACTIVE", "DISABLED");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAdminService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public UserAdminDtos.UserListResponse list() {
        List<UserAdminDtos.UserItem> items = userRepository.listManagedUsers().stream()
                .map(this::toItem)
                .toList();
        return new UserAdminDtos.UserListResponse(items, items.size());
    }

    public UserAdminDtos.UserItem get(int userId) {
        return toItem(requireUser(userId));
    }

    @Transactional
    public UserAdminDtos.UserItem create(UserAdminDtos.CreateRequest request) {
        String username = request.username().trim();
        String nickname = request.nickname().trim();
        String email = normalizeNullable(request.email());
        List<Integer> roleIds = resolveRoles(request.roles());
        LocalDateTime now = LocalDateTime.now();
        final long userId;
        try {
            userId = userRepository.insertUser(
                    username, passwordEncoder.encode(request.password()), nickname, email, "ACTIVE", now);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "用户名已存在");
        }
        userRepository.replaceRoles((int) userId, roleIds, CurrentUser.require().id(), now);
        return toItem(requireUser((int) userId));
    }

    public UserAdminDtos.UserItem update(int userId, UserAdminDtos.UpdateRequest request) {
        requireUser(userId);
        userRepository.updateProfile(
                userId, request.nickname().trim(), normalizeNullable(request.email()), LocalDateTime.now());
        return toItem(requireUser(userId));
    }

    @Transactional
    public UserAdminDtos.UserItem updateStatus(int userId, UserAdminDtos.StatusRequest request) {
        ManagedUser target = requireUser(userId);
        String status = request.status().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "用户状态不合法");
        }
        guardSelfAndLastAdmin(target, status, null);
        if (!status.equals(target.status())) {
            userRepository.updateStatusAndInvalidateTokens(userId, status, LocalDateTime.now());
        }
        return toItem(requireUser(userId));
    }

    @Transactional
    public UserAdminDtos.UserItem replaceRoles(int userId, UserAdminDtos.RolesRequest request) {
        ManagedUser target = requireUser(userId);
        List<Integer> roleIds = resolveRoles(request.roles());
        Set<String> codes = normalizeRoles(request.roles());
        guardSelfAndLastAdmin(target, target.status(), codes);
        userRepository.replaceRoles(userId, roleIds, CurrentUser.require().id(), LocalDateTime.now());
        userRepository.invalidateTokens(userId, LocalDateTime.now());
        return toItem(requireUser(userId));
    }

    public void resetPassword(int userId, UserAdminDtos.ResetPasswordRequest request) {
        requireUser(userId);
        userRepository.updatePasswordAndInvalidateTokens(
                userId, passwordEncoder.encode(request.newPassword()), LocalDateTime.now());
    }

    public void disable(int userId) {
        updateStatus(userId, new UserAdminDtos.StatusRequest("DISABLED"));
    }

    private void guardSelfAndLastAdmin(ManagedUser target, String newStatus, Set<String> newRoles) {
        AuthUser actor = CurrentUser.require();
        boolean removingActiveAdmin = "ACTIVE".equals(target.status())
                && userRepository.findRoleCodes(target.id()).contains("SYS_ADMIN")
                && ("DISABLED".equals(newStatus)
                    || (newRoles != null && !newRoles.contains("SYS_ADMIN")));
        if (!removingActiveAdmin) {
            return;
        }
        if (target.id() == actor.id()) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能禁用自己或移除自己的系统管理员角色");
        }
        if (userRepository.countActiveAdminsExcluding(target.id()) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "系统必须至少保留一个启用的系统管理员");
        }
    }

    private List<Integer> resolveRoles(List<String> rawRoles) {
        Set<String> codes = normalizeRoles(rawRoles);
        List<Integer> roleIds = userRepository.findActiveRoleIds(codes);
        if (roleIds.size() != codes.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "包含不存在或已禁用的角色");
        }
        return roleIds;
    }

    private Set<String> normalizeRoles(List<String> rawRoles) {
        Set<String> codes = new LinkedHashSet<>();
        if (rawRoles != null) {
            for (String raw : rawRoles) {
                if (raw == null) {
                    continue;
                }
                String code = raw.trim().toUpperCase(Locale.ROOT);
                if (!ALLOWED_ROLES.contains(code)) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "未知角色：" + code);
                }
                codes.add(code);
            }
        }
        if (codes.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "至少分配一个角色");
        }
        return codes;
    }

    private ManagedUser requireUser(int userId) {
        return userRepository.findManagedUser(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
    }

    private UserAdminDtos.UserItem toItem(ManagedUser user) {
        return new UserAdminDtos.UserItem(
                user.id(), user.username(), user.nickname(), user.email(), user.status(),
                userRepository.findRoleCodes(user.id()), user.lastLoginAt(), user.createdAt());
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
