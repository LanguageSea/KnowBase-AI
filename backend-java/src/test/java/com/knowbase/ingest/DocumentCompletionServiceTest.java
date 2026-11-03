package com.knowbase.ingest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import com.knowbase.doc.DocumentRepository;
import org.junit.jupiter.api.Test;

class DocumentCompletionServiceTest {

    @Test
    void successfulIngestMarksDocumentAndTaskInOneWorkflow() {
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        when(documents.isStatus(7, "PROCESSING")).thenReturn(true);
        when(documents.markActiveIfProcessing(anyLong(), anyInt(), any())).thenReturn(1);
        DocumentCompletionService service = new DocumentCompletionService(documents, tasks);

        service.completeIngest(11, 7, List.of(
                new DocumentRepository.ChunkRow(0, "正文", null, null, 2, "point-0")),
                LocalDateTime.now());

        verify(documents).replaceChunks(anyLong(), any(), any());
        verify(tasks).markSuccess(anyLong(), any());
    }

    @Test
    void deletingDuringIndexWriteDiscardsTheEnterpriseResult() {
        DocumentRepository documents = mock(DocumentRepository.class);
        IngestionTaskRepository tasks = mock(IngestionTaskRepository.class);
        when(documents.isStatus(7, "PROCESSING")).thenReturn(true);
        when(documents.markActiveIfProcessing(anyLong(), anyInt(), any())).thenReturn(0);
        DocumentCompletionService service = new DocumentCompletionService(documents, tasks);

        assertThatThrownBy(() -> service.completeIngest(
                11, 7,
                List.of(new DocumentRepository.ChunkRow(0, "正文", null, null, 2, "point-0")),
                LocalDateTime.now()))
                .isInstanceOf(TaskCancelledException.class);
    }
}
