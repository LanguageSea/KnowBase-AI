package com.knowbase.ingest;

import java.time.LocalDateTime;
import java.util.List;

import com.knowbase.doc.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档完成动作与任务终态在同一事务内提交；外部 Python 调用不放在事务中。
 */
@Service
public class DocumentCompletionService {

    private final DocumentRepository documentRepository;
    private final IngestionTaskRepository taskRepository;

    public DocumentCompletionService(DocumentRepository documentRepository,
            IngestionTaskRepository taskRepository) {
        this.documentRepository = documentRepository;
        this.taskRepository = taskRepository;
    }

    @Transactional
    public void completeIngest(long taskId, long docId,
            List<DocumentRepository.ChunkRow> chunks, LocalDateTime now) {
        if (!documentRepository.isStatus(docId, "PROCESSING")) {
            throw new TaskCancelledException("文档已不再处于 PROCESSING");
        }
        documentRepository.replaceChunks(docId, chunks, now);
        int changed = documentRepository.markActiveIfProcessing(docId, chunks.size(), now);
        if (changed != 1) {
            throw new TaskCancelledException("文档状态已变化，索引结果被丢弃");
        }
        taskRepository.markSuccess(taskId, now);
    }

    @Transactional
    public void completeDelete(long taskId, long docId, LocalDateTime now) {
        if (!documentRepository.isStatus(docId, "DELETING")) {
            throw new TaskCancelledException("文档已不再处于 DELETING");
        }
        // 引用必须先于分片释放 RESTRICT 外键；文档删除后旧引用也不可再访问。
        documentRepository.deleteCitationsForDocument(docId);
        documentRepository.deleteChunks(docId);
        documentRepository.markDeleted(docId, now);
        taskRepository.markSuccess(taskId, now);
    }
}
