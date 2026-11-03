package com.knowbase.kb;

import java.util.List;
import java.util.Set;

import com.knowbase.auth.AuthUser;
import com.knowbase.auth.CurrentUser;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 知识库资源级权限的唯一权威实现。Python 侧不做任何权限判断。
 *
 * <p>owner 等价于 MANAGER；成员角色只认数据库当前值，撤权后下一个请求立即生效。
 */
@Service
public class AuthzService {

    private static final Set<String> WRITE_ROLES = Set.of("EDITOR", "MANAGER");
    private static final Set<String> MANAGE_ROLES = Set.of("MANAGER");

    private final KbRepository kbRepository;

    public AuthzService(KbRepository kbRepository) {
        this.kbRepository = kbRepository;
    }

    public boolean canReadKb(int userId, long kbId) {
        Access access = access(userId, kbId);
        return access.active() && (access.owner() || access.role() != null);
    }

    public boolean canWriteKb(int userId, long kbId) {
        Access access = access(userId, kbId);
        return access.active() && (access.owner() || WRITE_ROLES.contains(access.role()));
    }

    public boolean canManageKb(int userId, long kbId) {
        Access access = access(userId, kbId);
        return access.active() && (access.owner() || MANAGE_ROLES.contains(access.role()));
    }

    public void requireReadKb(long kbId) {
        AuthUser user = CurrentUser.require();
        require(canReadKb(user.id(), kbId), "无权读取该知识库");
    }

    public void requireWriteKb(long kbId) {
        AuthUser user = CurrentUser.require();
        require(canWriteKb(user.id(), kbId), "无权修改该知识库");
    }

    public void requireManageKb(long kbId) {
        AuthUser user = CurrentUser.require();
        require(canManageKb(user.id(), kbId), "无权管理该知识库成员");
    }

    public List<Long> readableKbIds(int userId) {
        return kbRepository.findReadableIds(userId);
    }

    private Access access(int userId, long kbId) {
        KbRepository.KbAccess row = kbRepository.findAccess(kbId, userId).orElse(null);
        if (row == null) {
            return new Access(false, false, null);
        }
        return new Access(
                "ACTIVE".equals(row.status()),
                row.ownerId() == userId,
                row.memberRole());
    }

    private static void require(boolean allowed, String message) {
        if (!allowed) {
            throw new BusinessException(ErrorCode.FORBIDDEN, message);
        }
    }

    private record Access(boolean active, boolean owner, String role) {
    }
}
