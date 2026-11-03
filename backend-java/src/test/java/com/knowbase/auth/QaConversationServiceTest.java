package com.knowbase.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.doc.DocumentRepository;
import com.knowbase.kb.AuthzService;
import com.knowbase.qa.QaConversationService;
import com.knowbase.qa.QaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QaConversationServiceTest {

    private QaRepository repository;
    private AuthzService authzService;
    private DocumentRepository documents;
    private QaConversationService service;

    @BeforeEach
    void setUp() {
        CurrentUser.set(new AuthUser(3, "alice", "Alice", List.of("USER")));
        repository = mock(QaRepository.class);
        authzService = mock(AuthzService.class);
        documents = mock(DocumentRepository.class);
        service = new QaConversationService(repository, authzService, documents);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    void anotherUsersConversationIsNotFound() {
        when(repository.findConversation(88, 3)).thenReturn(null);

        assertThatThrownBy(() -> service.detail(88, 1, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void revokedCitationPermissionReturnsForbiddenBeforeReadingChunk() {
        QaRepository.CitationRow citation = new QaRepository.CitationRow(
                9, 20, 501, 10, "制度.txt", 4, 1, 2, 0.8, "旧引用", "ACTIVE");
        when(repository.findCitationForUser(9, 3)).thenReturn(citation);
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权读取该知识库"))
                .when(authzService).requireReadKb(4);

        assertThatThrownBy(() -> service.citationSource(9))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(documents, never()).findChunk(10, 2);
    }
}
