package com.knowbase.system;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import com.knowbase.auth.Permission;
import com.knowbase.auth.RequirePermission;
import com.knowbase.common.ApiResponse;
import com.knowbase.health.HealthReport;
import com.knowbase.health.HealthService;
import com.knowbase.model.AiCallLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/system")
@RequirePermission(Permission.SYSTEM_MONITOR)
public class SystemStatusController {

    private final HealthService healthService;
    private final SystemStatusRepository repository;
    private final AiCallLogRepository callLogRepository;

    public SystemStatusController(HealthService healthService,
            SystemStatusRepository repository, AiCallLogRepository callLogRepository) {
        this.healthService = healthService;
        this.repository = repository;
        this.callLogRepository = callLogRepository;
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatusResponse> status() {
        HealthReport health = healthService.check();
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<ErrorCountItem> taskErrors = repository.topTaskErrors(since, 5).stream()
                .map(row -> new ErrorCountItem(row.code(), row.count()))
                .toList();
        List<ErrorCountItem> aiErrors = callLogRepository.aggregateErrors(since, 5).stream()
                .map(row -> new ErrorCountItem(row.errorType(), row.count()))
                .toList();
        return ApiResponse.ok(new SystemStatusResponse(
                health.status(),
                Instant.now().toString(),
                health.components(),
                new TaskBacklog(
                        repository.countTasks("QUEUED"),
                        repository.countTasks("RUNNING"),
                        repository.countTasks("FAILED"),
                        repository.countTasks("DEAD"),
                        repository.countFailedDocuments()),
                taskErrors,
                aiErrors));
    }

    public record SystemStatusResponse(String status, String checkedAt,
            List<com.knowbase.health.ComponentHealth> components,
            TaskBacklog backlog, List<ErrorCountItem> taskErrorTop5,
            List<ErrorCountItem> aiErrorTop5) {
    }

    public record TaskBacklog(long queued, long running, long failed, long dead,
            long failedDocuments) {
    }

    public record ErrorCountItem(String code, long count) {
    }
}
