package com.knowbase.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import com.knowbase.ai.PythonClient;
import com.knowbase.auth.UserRepository;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.config.QaProperties;
import com.knowbase.config.StorageProperties;
import com.knowbase.doc.DocumentRepository;
import com.knowbase.doc.DocumentService;
import com.knowbase.ingest.IngestionTaskRepository;
import com.knowbase.kb.AuthzService;
import com.knowbase.kb.KbRepository;
import com.knowbase.kb.KbService;
import com.knowbase.qa.QaDtos;
import com.knowbase.qa.QaCitationResolver;
import com.knowbase.qa.QaRateLimiter;
import com.knowbase.qa.QaRepository;
import com.knowbase.qa.QaService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** T2.6 六条越权矩阵，全部在进入外部服务或 Repository 查询前拒绝。 */
class KbIsolationTest {

    private AuthzService authzService;

    @BeforeEach
    void setUp() {
        CurrentUser.set(new AuthUser(3, "alice", "Alice", List.of("USER")));
        authzService = mock(AuthzService.class);
    }

    @AfterEach
    void tearDown() {
        CurrentUser.clear();
    }

    @Test
    void changedPathKbIdIsForbiddenBeforeDocumentLookup() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权读取该知识库"))
                .when(authzService).requireReadKb(99);
        DocumentRepository documents = mock(DocumentRepository.class);
        DocumentService service = documentService(documents);

        assertThatThrownBy(() -> service.get(99, 7))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(documents, org.mockito.Mockito.never()).findById(7);
    }

    @Test
    void changedBodyKbIdInQuestionIsForbiddenBeforeSearch() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权读取该知识库"))
                .when(authzService).requireReadKb(99);
        PythonClient python = mock(PythonClient.class);
        QaService qa = new QaService(
                authzService, mock(UserRepository.class), mock(QaRepository.class),
                python, mock(QaRateLimiter.class), mock(QaCitationResolver.class),
                mock(TransactionTemplate.class), mock(ExecutorService.class),
                new QaProperties(5, 0.2, 20, 3000, 8, 90000L));

        assertThatThrownBy(() -> qa.ask(new QaDtos.AskRequest(99L, null, "问题")))
                .isInstanceOf(BusinessException.class);
        verify(python, org.mockito.Mockito.never()).search(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                anyInt(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void documentIdFromAnotherKbCannotBeReadThroughAuthorizedPath() {
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findById(7)).thenReturn(Optional.of(document(7, 10, "ACTIVE")));
        DocumentService service = documentService(documents);

        assertThatThrownBy(() -> service.get(11, 7))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void querySequenceCannotReadAnotherDocumentsChunk() {
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findById(7)).thenReturn(Optional.of(document(7, 10, "ACTIVE")));
        when(documents.findChunk(7, 999)).thenReturn(Optional.empty());
        DocumentService service = documentService(documents);

        assertThatThrownBy(() -> service.chunk(10, 7, 999))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
        verify(authzService).requireReadKb(10);
    }

    @Test
    void managerCanDeleteAnyDocumentInManagedKnowledgeBase() {
        CurrentUser.set(new AuthUser(2, "manager", "Manager", List.of("USER")));
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        when(documents.findById(7)).thenReturn(Optional.of(document(7, 10, "ACTIVE")));
        when(documents.markDeleting(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any())).thenReturn(1);
        when(tasks.findLatest(7, IngestionTaskRepository.TaskType.INGEST))
                .thenReturn(Optional.empty());
        DocumentService service = new DocumentService(
                authzService, documents, tasks, new StorageProperties("target/test-uploads"));

        service.delete(10, 7);

        verify(authzService).requireWriteKb(10);
        verify(tasks).cancelQueuedOrRunning(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any());
        verify(tasks).insertQueued(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(IngestionTaskRepository.TaskType.DELETE),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void removedMemberLosesAccessOnTheNextRequest() {
        KbRepository repository = mock(KbRepository.class);
        when(repository.findAccess(10, 3)).thenReturn(Optional.of(
                new KbRepository.KbAccess(10, 1, "ACTIVE", null)));
        AuthzService realAuthz = new AuthzService(repository);

        assertThat(realAuthz.canReadKb(3, 10)).isFalse();
    }

    @Test
    void archivedKbIsExcludedFromListAndDirectAccess() {
        KbRepository repository = mock(KbRepository.class);
        when(repository.findAccess(10, 3)).thenReturn(Optional.of(
                new KbRepository.KbAccess(10, 1, "ARCHIVED", "VIEWER")));
        when(authzService.readableKbIds(3)).thenReturn(List.of());
        when(repository.listReadable(3)).thenReturn(List.of());
        KbService kbService = new KbService(repository, authzService);

        assertThat(new AuthzService(repository).canReadKb(3, 10)).isFalse();
        assertThat(kbService.list().items()).isEmpty();
        verify(repository).listReadable(3);
    }

    private DocumentService documentService(DocumentRepository documents) {
        return new DocumentService(
                authzService, documents, mock(IngestionTaskRepository.class),
                new StorageProperties("target/test-uploads"));
    }

    private static DocumentRepository.DocumentRow document(long id, long kbId, String status) {
        return new DocumentRepository.DocumentRow(
                id, kbId, "a.txt", "txt", 1, "hash", "2026/10/a.txt",
                status, 1, null, null, "2026-10-31", "2026-10-31");
    }
}
