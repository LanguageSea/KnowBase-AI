package com.knowbase.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.knowbase.auth.AuthUser;
import com.knowbase.auth.CurrentUserTestSupport;
import com.knowbase.kb.AuthzService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorkspaceSummaryServiceTest {

    @AfterEach
    void clear() {
        CurrentUserTestSupport.clear();
    }

    @Test
    void summaryIsAlwaysScopedToCurrentUser() {
        CurrentUserTestSupport.authenticate(
                new AuthUser(7, "alice", "Alice", List.of("USER")));
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        AuthzService authz = mock(AuthzService.class);
        when(authz.readableKbIds(7)).thenReturn(List.of(1L, 2L));
        when(repository.countAccessibleDocuments(7)).thenReturn(6L);
        when(repository.countConversations(7)).thenReturn(3L);
        when(repository.countQuestions(7)).thenReturn(9L);
        when(repository.countProcessingDocuments(7)).thenReturn(2L);
        when(repository.countQuestionsByDay(7,
                LocalDate.now().minusDays(6).atStartOfDay())).thenReturn(List.of());
        when(repository.latestDocuments(7, 5)).thenReturn(List.of(
                new WorkspaceRepository.LatestDocumentRow(
                        10L, 1L, "产品库", "说明.txt", "ACTIVE", "2026-10-31T00:00:00")));

        WorkspaceSummaryService.WorkspaceSummary summary =
                new WorkspaceSummaryService(repository, authz).summary();

        assertThat(summary.scope()).isEqualTo("PERSONAL");
        assertThat(summary.accessibleKbCount()).isEqualTo(2);
        assertThat(summary.activeDocumentCount()).isEqualTo(6);
        assertThat(summary.dailyQuestions()).hasSize(7);
        assertThat(summary.latestDocuments()).singleElement()
                .satisfies(doc -> assertThat(doc.kbName()).isEqualTo("产品库"));
    }
}
