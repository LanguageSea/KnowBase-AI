package com.knowbase.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class IngestionTaskRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void claimUsesConditionalUpdateAndIncrementsAttempt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.query(anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of(new IngestionTaskRepository.TaskCandidate(11, 7, 1, 3)));
        IngestionTaskRepository repository = new IngestionTaskRepository(jdbc);

        var claimed = repository.claimNext(IngestionTaskRepository.TaskType.INGEST, "worker-a");

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().attempt()).isEqualTo(1);
        assertThat(claimed.orElseThrow().docId()).isEqualTo(7);
    }

    @Test
    @SuppressWarnings("unchecked")
    void affectedRowsZeroMeansAnotherWorkerWon() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        IngestionTaskRepository repository = new IngestionTaskRepository(jdbc);

        assertThat(repository.claimNext(IngestionTaskRepository.TaskType.INGEST, "worker-b")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoWorkersCannotBothClaimWhenSecondConditionalUpdateLoses() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("UPDATE kb_ingestion_task SET status = 'RUNNING'")) {
                return 0;
            }
            if (sql.contains("UPDATE kb_document")) {
                return 1;
            }
            return 0;
        });
        when(jdbc.query(anyString(), any(RowMapper.class), anyString()))
                .thenReturn(List.of(new IngestionTaskRepository.TaskCandidate(21, 9, 1, 5)));
        IngestionTaskRepository repository = new IngestionTaskRepository(jdbc);

        assertThat(repository.claimNext(IngestionTaskRepository.TaskType.DELETE, "worker-b")).isEmpty();
        assertThat(repository.claimNext(IngestionTaskRepository.TaskType.DELETE, "worker-c")).isEmpty();
    }
}
