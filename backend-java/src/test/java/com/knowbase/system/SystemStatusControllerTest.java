package com.knowbase.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import com.knowbase.common.ApiResponse;
import com.knowbase.health.ComponentHealth;
import com.knowbase.health.HealthReport;
import com.knowbase.health.HealthService;
import com.knowbase.model.AiCallLogRepository;
import org.junit.jupiter.api.Test;

class SystemStatusControllerTest {

    @Test
    void healthBacklogAndTaskErrorCountsAreReturnedTogether() {
        HealthService healthService = mock(HealthService.class);
        SystemStatusRepository repository = mock(SystemStatusRepository.class);
        AiCallLogRepository callLogs = mock(AiCallLogRepository.class);
        when(healthService.check()).thenReturn(new HealthReport(
                "DEGRADED",
                Instant.now().toString(),
                List.of(ComponentHealth.up("mysql", 1),
                        ComponentHealth.down("python", 2, "CONNECTION_REFUSED"))));
        when(repository.countTasks("QUEUED")).thenReturn(2L);
        when(repository.countTasks("RUNNING")).thenReturn(1L);
        when(repository.countTasks("FAILED")).thenReturn(5L);
        when(repository.countTasks("DEAD")).thenReturn(1L);
        when(repository.countFailedDocuments()).thenReturn(5L);
        when(repository.topTaskErrors(any(LocalDateTime.class), anyInt())).thenReturn(List.of(
                new SystemStatusRepository.ErrorCount("PARSE_TIMEOUT", 5)));
        when(callLogs.aggregateErrors(any(LocalDateTime.class), anyInt())).thenReturn(List.of(
                new AiCallLogRepository.ErrorAggregateRow("HTTP_401", 2)));

        ApiResponse<SystemStatusController.SystemStatusResponse> response =
                new SystemStatusController(healthService, repository, callLogs).status();

        assertThat(response.data().status()).isEqualTo("DEGRADED");
        assertThat(response.data().components())
                .extracting(ComponentHealth::name)
                .containsExactly("mysql", "python");
        assertThat(response.data().backlog().failed()).isEqualTo(5);
        assertThat(response.data().backlog().failedDocuments()).isEqualTo(5);
        assertThat(response.data().taskErrorTop5())
                .singleElement()
                .satisfies(item -> assertThat(item.count()).isEqualTo(5));
    }
}
