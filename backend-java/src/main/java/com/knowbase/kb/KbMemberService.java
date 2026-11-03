package com.knowbase.kb;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.knowbase.auth.AuthUser;
import com.knowbase.auth.CurrentUser;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KbMemberService {

    private final AuthzService authzService;
    private final KbRepository kbRepository;

    public KbMemberService(AuthzService authzService, KbRepository kbRepository) {
        this.authzService = authzService;
        this.kbRepository = kbRepository;
    }

    public KbMemberDtos.MemberListResponse list(long kbId) {
        authzService.requireReadKb(kbId);
        List<KbMemberDtos.MemberItem> items = kbRepository.listMembers(kbId).stream()
                .map(row -> new KbMemberDtos.MemberItem(
                        row.userId(), row.username(), row.nickname(), row.role(), row.createdAt()))
                .toList();
        return new KbMemberDtos.MemberListResponse(items, items.size());
    }

    public List<KbMemberDtos.UserCandidate> searchCandidates(long kbId, String keyword) {
        authzService.requireManageKb(kbId);
        Set<Integer> memberIds = kbRepository.listMembers(kbId).stream()
                .map(KbRepository.MemberRow::userId)
                .collect(Collectors.toSet());
        Long ownerId = kbRepository.findOwnerId(kbId);
        return kbRepository.searchActiveUsers(keyword).stream()
                .filter(row -> ownerId == null || row.userId() != ownerId)
                .filter(row -> !memberIds.contains(row.userId()))
                .map(row -> new KbMemberDtos.UserCandidate(
                        row.userId(), row.username(), row.nickname()))
                .toList();
    }

    @Transactional
    public KbMemberDtos.MemberItem grant(long kbId, KbMemberDtos.GrantRequest request) {
        authzService.requireManageKb(kbId);
        AuthUser operator = CurrentUser.require();
        int targetUserId = request.userId();
        if (!kbRepository.activeUserExists(targetUserId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目标用户不存在或已禁用");
        }
        guardOwner(kbId, targetUserId);
        String newRole = normalizeRole(request.role());
        String oldRole = kbRepository.findMember(kbId, targetUserId)
                .map(KbRepository.MemberRow::role)
                .orElse(null);
        LocalDateTime now = LocalDateTime.now();
        kbRepository.upsertMember(kbId, targetUserId, newRole, operator.id(), now);
        kbRepository.insertGrantAudit(
                kbId, targetUserId, oldRole == null ? "GRANT" : "ROLE_CHANGE",
                oldRole, newRole, operator.id(), now);
        return toItem(kbRepository.findMember(kbId, targetUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "成员授权后查询失败")));
    }

    @Transactional
    public KbMemberDtos.MemberItem changeRole(long kbId, int userId, KbMemberDtos.RoleRequest request) {
        authzService.requireManageKb(kbId);
        KbRepository.MemberRow existing = requireMember(kbId, userId);
        guardOwner(kbId, userId);
        String newRole = normalizeRole(request.role());
        AuthUser operator = CurrentUser.require();
        LocalDateTime now = LocalDateTime.now();
        kbRepository.upsertMember(kbId, userId, newRole, operator.id(), now);
        kbRepository.insertGrantAudit(
                kbId, userId, "ROLE_CHANGE", existing.role(), newRole, operator.id(), now);
        return toItem(requireMember(kbId, userId));
    }

    @Transactional
    public void revoke(long kbId, int userId) {
        authzService.requireManageKb(kbId);
        KbRepository.MemberRow existing = requireMember(kbId, userId);
        guardOwner(kbId, userId);
        AuthUser operator = CurrentUser.require();
        kbRepository.deleteMember(kbId, userId);
        kbRepository.insertGrantAudit(
                kbId, userId, "REVOKE", existing.role(), null, operator.id(), LocalDateTime.now());
    }

    private KbRepository.MemberRow requireMember(long kbId, int userId) {
        return kbRepository.findMember(kbId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库成员不存在"));
    }

    private void guardOwner(long kbId, int userId) {
        KbRepository.KbAccess access = kbRepository.findAccess(kbId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        if (access.ownerId() == userId) {
            throw new BusinessException(ErrorCode.CONFLICT, "不能修改或移除知识库所有者");
        }
    }

    private static String normalizeRole(String role) {
        String value = role == null ? "" : role.trim().toUpperCase();
        if (!List.of("VIEWER", "EDITOR", "MANAGER").contains(value)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "成员角色不合法");
        }
        return value;
    }

    private static KbMemberDtos.MemberItem toItem(KbRepository.MemberRow row) {
        return new KbMemberDtos.MemberItem(
                row.userId(), row.username(), row.nickname(), row.role(), row.createdAt());
    }
}
