package com.knowbase.model;

import java.time.LocalDateTime;
import java.util.List;

import com.knowbase.common.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AiCallLogService {

    private static final Logger log = LoggerFactory.getLogger(AiCallLogService.class);

    private final AiCallLogRepository repository;

    public AiCallLogService(AiCallLogRepository repository) {
        this.repository = repository;
    }

    public void record(Long modelConfigId, String scene, boolean success, long latencyMs,
            String errorType, String errorMessage) {
        try {
            int safeLatency = latencyMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, latencyMs);
            repository.insert(modelConfigId, scene, success, safeLatency, null, null,
                    errorType, errorMessage, TraceContext.current(), LocalDateTime.now());
        } catch (RuntimeException ex) {
            // Observability must not turn a successful model call into a failed business operation.
            log.warn("写入模型调用日志失败 scene={} error={}", scene, ex.getClass().getSimpleName());
        }
    }

    public ModelStatsResponse stats(int days) {
        int safeDays = Math.max(1, Math.min(days, 90));
        LocalDateTime since = LocalDateTime.now().minusDays(safeDays).withHour(0).withMinute(0)
                .withSecond(0).withNano(0);
        long total = repository.countSince(since);
        long success = repository.countSuccessSince(since, true);
        List<ModelStatsItem> byModel = repository.aggregateByModel(since).stream()
                .map(row -> new ModelStatsItem(
                        row.modelConfigId(),
                        row.provider() == null ? "环境变量" : row.provider(),
                        row.modelType() == null ? "UNKNOWN" : row.modelType(),
                        row.modelName() == null ? "默认模型" : row.modelName(),
                        row.callCount(), row.successCount(),
                        row.callCount() == 0 ? 0 : (double) row.successCount() / row.callCount(),
                        round(row.avgLatencyMs())))
                .toList();
        List<DailyCallItem> daily = repository.aggregateByDay(since).stream()
                .map(row -> new DailyCallItem(row.day(), row.callCount(), row.successCount(),
                        round(row.avgLatencyMs())))
                .toList();
        return new ModelStatsResponse(safeDays, total, success, total - success, byModel, daily);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public record ModelStatsResponse(int days, long totalCalls, long successCalls,
            long failedCalls, List<ModelStatsItem> byModel, List<DailyCallItem> daily) {
    }

    public record ModelStatsItem(Long modelConfigId, String provider, String modelType,
            String modelName, long calls, long successes, double successRate,
            double avgLatencyMs) {
    }

    public record DailyCallItem(String day, long calls, long successes, double avgLatencyMs) {
    }
}
