package com.knowbase.kb;

import java.time.LocalDateTime;
import java.util.List;

import com.knowbase.auth.AuthUser;
import com.knowbase.auth.CurrentUser;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库业务（docs/API_SPEC.md §2）。
 *
 * <p>列表和单库读取统一走 {@link AuthzService}；创建者自动成为 MANAGER。
 */
@Service
public class KbService {

    private static final Logger log = LoggerFactory.getLogger(KbService.class);

    private final KbRepository kbRepository;
    private final AuthzService authzService;

    public KbService(KbRepository kbRepository, AuthzService authzService) {
        this.kbRepository = kbRepository;
        this.authzService = authzService;
    }

    public KbDtos.KbListResponse list() {
        AuthUser user = CurrentUser.require();
        authzService.readableKbIds(user.id());
        List<KbDtos.KbItem> items = kbRepository.listReadable(user.id()).stream()
                .map(row -> new KbDtos.KbItem(
                        row.id(), kbRepository.findOwnerId(row.id()), row.name(), row.description(),
                        row.docCount(), authzService.canWriteKb(user.id(), row.id()),
                        authzService.canManageKb(user.id(), row.id()), row.createdAt()))
                .toList();
        return new KbDtos.KbListResponse(items, items.size());
    }

    @Transactional
    public KbDtos.KbItem create(KbDtos.CreateRequest request) {
        AuthUser user = CurrentUser.require();
        String name = request.name().trim();
        if (name.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "知识库名称不能为空");
        }
        if (name.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "知识库名称不能超过 128 个字符");
        }
        String description = request.description() == null ? null : request.description().trim();
        LocalDateTime now = LocalDateTime.now();
        final long kbId;
        try {
            kbId = kbRepository.insert(user.id(), name, description, now);
            kbRepository.insertOwnerMember(kbId, user.id(), now);
        } catch (DuplicateKeyException ex) {
            log.info("建库重名 userId={} name={}", user.id(), name);
            throw new BusinessException(ErrorCode.CONFLICT, "已存在同名知识库");
        }
        KbRepository.KbRow row = kbRepository.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "建库后查询失败"));
        return new KbDtos.KbItem(row.id(), user.id(), row.name(), row.description(), 0,
                true, true, row.createdAt());
    }

    /** 取单个库；VIEWER 及以上可读。 */
    public KbDtos.KbItem get(long kbId) {
        authzService.requireReadKb(kbId);
        KbRepository.KbRow row = kbRepository.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
        long docCount = kbRepository.countActiveDocs(kbId);
        AuthUser user = CurrentUser.require();
        return new KbDtos.KbItem(row.id(), kbRepository.findOwnerId(kbId), row.name(),
                row.description(), docCount, authzService.canWriteKb(user.id(), kbId),
                authzService.canManageKb(user.id(), kbId), row.createdAt());
    }

    /** 兼容旧调用点：Stage 2 起 owner 与 MANAGER 成员都视为可管理。 */
    public KbRepository.KbRow requireOwned(long kbId) {
        authzService.requireManageKb(kbId);
        return kbRepository.findById(kbId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
    }
}
