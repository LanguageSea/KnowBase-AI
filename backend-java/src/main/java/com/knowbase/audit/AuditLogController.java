package com.knowbase.audit;

import java.util.List;

import com.knowbase.auth.Permission;
import com.knowbase.auth.RequirePermission;
import com.knowbase.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit-logs")
@RequirePermission(Permission.SYSTEM_MONITOR)
public class AuditLogController {

    private final OperationLogRepository repository;

    public AuditLogController(OperationLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<AuditLogPage> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String result) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        long total = repository.count(action, result);
        List<AuditLogItem> items = repository.list(action, result,
                        (safePage - 1) * safeSize, safeSize).stream()
                .map(row -> new AuditLogItem(
                        row.id(), row.operatorId(), row.username(), row.action(),
                        row.resourceType(), row.resourceId(), row.result(), row.detail(),
                        row.ip(), row.traceId(), row.createdAt()))
                .toList();
        return ApiResponse.ok(new AuditLogPage(items, total, safePage, safeSize));
    }

    public record AuditLogPage(List<AuditLogItem> items, long total, int page, int size) {
    }

    public record AuditLogItem(long id, Integer operatorId, String username, String action,
            String resourceType, String resourceId, String result, String detail,
            String ip, String traceId, String createdAt) {
    }
}
