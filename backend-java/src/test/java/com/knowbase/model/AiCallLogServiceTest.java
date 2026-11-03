package com.knowbase.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import com.knowbase.model.AiCallLogRepository.DailyAggregateRow;
import com.knowbase.model.AiCallLogRepository.ModelAggregateRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiCallLogServiceTest {

    @Mock
    private AiCallLogRepository repository;

    @Test
    void statisticsMatchRawLogCountsAndAggregates() {
        when(repository.countSince(any(LocalDateTime.class))).thenReturn(5L);
        when(repository.countSuccessSince(any(LocalDateTime.class), org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(4L);
        when(repository.aggregateByModel(any(LocalDateTime.class))).thenReturn(List.of(
                new ModelAggregateRow(7L, "deepseek", "CHAT", "deepseek-chat", 4, 3, 321.126)));
        when(repository.aggregateByDay(any(LocalDateTime.class))).thenReturn(List.of(
                new DailyAggregateRow("2026-10-31", 5, 4, 180.555)));

        AiCallLogService.ModelStatsResponse stats =
                new AiCallLogService(repository).stats(7);

        assertThat(stats.totalCalls()).isEqualTo(5);
        assertThat(stats.successCalls()).isEqualTo(4);
        assertThat(stats.failedCalls()).isEqualTo(1);
        assertThat(stats.byModel()).singleElement().satisfies(item -> {
            assertThat(item.calls()).isEqualTo(4);
            assertThat(item.successRate()).isEqualTo(0.75);
            assertThat(item.avgLatencyMs()).isEqualTo(321.13);
        });
        assertThat(stats.daily()).singleElement().satisfies(item ->
                assertThat(item.avgLatencyMs()).isEqualTo(180.56));
    }
}
