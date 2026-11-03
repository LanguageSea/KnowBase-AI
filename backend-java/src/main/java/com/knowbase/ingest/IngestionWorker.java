package com.knowbase.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.knowbase.ai.AiRemoteException;
import com.knowbase.ai.PythonClient;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.config.StorageProperties;
import com.knowbase.doc.DocumentRepository;
import com.knowbase.doc.PointIds;
import com.knowbase.ingest.IngestionTaskRepository.ClaimedTask;
import com.knowbase.ingest.IngestionTaskRepository.TaskType;
import com.knowbase.model.AiCallLogService;
import com.knowbase.model.ModelConfigService;
import com.knowbase.model.ModelType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文档任务执行器。每个实例有独立 workerId；任务认领由数据库条件 UPDATE 决定，
 * 因此横向启动多个 Java 进程也不会重复执行同一任务。
 */
@Component
public class IngestionWorker {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
    private static final int BATCH_SIZE = 5;

    private final IngestionTaskRepository taskRepository;
    private final DocumentRepository documentRepository;
    private final DocumentCompletionService completionService;
    private final PythonClient pythonClient;
    private final ModelConfigService modelConfigService;
    private final AiCallLogService callLogService;
    private final Path uploadRoot;
    private final String workerId;

    public IngestionWorker(IngestionTaskRepository taskRepository,
            DocumentRepository documentRepository,
            DocumentCompletionService completionService,
            PythonClient pythonClient,
            ModelConfigService modelConfigService,
            AiCallLogService callLogService,
            StorageProperties storageProperties) {
        this.taskRepository = taskRepository;
        this.documentRepository = documentRepository;
        this.completionService = completionService;
        this.pythonClient = pythonClient;
        this.modelConfigService = modelConfigService;
        this.callLogService = callLogService;
        this.uploadRoot = Path.of(storageProperties.uploadDir()).toAbsolutePath().normalize();
        this.workerId = "java-" + UUID.randomUUID().toString().substring(0, 12);
    }

    @Scheduled(fixedDelayString = "${knowbase.tasks.poll-interval-ms:2000}")
    public void poll() {
        try {
            drain(TaskType.INGEST);
            drain(TaskType.DELETE);
        } catch (RuntimeException ex) {
            log.warn("任务轮询异常 error={}", ex.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${knowbase.tasks.stale-scan-interval-ms:30000}")
    public void recoverStaleTasks() {
        try {
            int recovered = taskRepository.recoverStale(
                    LocalDateTime.now().minusMinutes(10), LocalDateTime.now());
            if (recovered > 0) {
                log.warn("回收超时任务 count={}", recovered);
            }
        } catch (RuntimeException ex) {
            log.warn("超时任务回收失败 error={}", ex.getClass().getSimpleName());
        }
    }

    /** 删除失败每 5 分钟重新排队，最多 5 次；耗尽后保持 DEAD，等待人工介入。 */
    @Scheduled(fixedDelayString = "${knowbase.tasks.delete-compensation-interval-ms:300000}")
    public void compensateFailedDeletes() {
        try {
            int requeued = taskRepository.requeueFailedDeletes(LocalDateTime.now());
            if (requeued > 0) {
                log.info("删除补偿重新排队 count={}", requeued);
            }
        } catch (RuntimeException ex) {
            log.warn("删除补偿失败 error={}", ex.getClass().getSimpleName());
        }
    }

    private void drain(TaskType type) {
        for (int i = 0; i < BATCH_SIZE; i++) {
            ClaimedTask task = taskRepository.claimNext(type, workerId).orElse(null);
            if (task == null) {
                return;
            }
            if (type == TaskType.INGEST) {
                executeIngest(task);
            } else {
                executeDelete(task);
            }
        }
    }

    private void executeIngest(ClaimedTask task) {
        long started = System.currentTimeMillis();
        try {
            DocumentRepository.DocumentRow document = requireDocument(task.docId());
            if (!"PROCESSING".equals(document.status())) {
                taskRepository.markCancelled(task.id(), LocalDateTime.now());
                return;
            }
            Path source = resolveStoragePath(document.storageKey());
            List<Map<String, Object>> chunks = pythonClient.parse(
                    document.id(), document.kbId(), source.toString(), document.name(), document.ext());
            if (chunks.isEmpty()) {
                throw new AiRemoteException("NO_TEXT_LAYER", "文档解析后无有效文本，暂不支持 OCR");
            }
            List<Map<String, Object>> indexChunks = new ArrayList<>();
            List<DocumentRepository.ChunkRow> rows = new ArrayList<>();
            for (Map<String, Object> chunk : chunks) {
                int seq = number(chunk.get("seq")).intValue();
                String content = String.valueOf(chunk.get("content"));
                Integer pageNo = nullableInt(chunk.get("page_no"));
                String heading = nullableString(chunk.get("heading"));
                Integer tokenCount = nullableInt(chunk.get("token_count"));
                if (tokenCount == null) {
                    tokenCount = nullableInt(chunk.get("char_count"));
                }
                indexChunks.add(Map.of("seq", seq, "content", content));
                rows.add(new DocumentRepository.ChunkRow(
                        seq, content, pageNo, heading, tokenCount,
                        PointIds.forChunk(document.id(), seq)));
            }
            ModelConfigService.RuntimeSelection embeddingModel =
                    modelConfigService.runtimeFor(ModelType.EMBEDDING).orElse(null);
            long modelStarted = System.nanoTime();
            boolean modelSuccess = false;
            try {
                pythonClient.index(document.id(), document.kbId(), indexChunks,
                        embeddingModel == null ? null : embeddingModel.payload());
                modelSuccess = true;
            } catch (RuntimeException ex) {
                callLogService.record(
                        embeddingModel == null ? null : embeddingModel.configId(),
                        "EMBED", false, elapsedMs(modelStarted), errorType(ex), ex.getMessage());
                throw ex;
            }
            callLogService.record(
                    embeddingModel == null ? null : embeddingModel.configId(),
                    "EMBED", modelSuccess, elapsedMs(modelStarted), null, null);
            completionService.completeIngest(task.id(), document.id(), rows, LocalDateTime.now());
            log.info("文档索引成功 docId={} attempt={} chunks={} elapsedMs={}",
                    document.id(), task.attempt(), rows.size(), System.currentTimeMillis() - started);
        } catch (TaskCancelledException ex) {
            taskRepository.markCancelled(task.id(), LocalDateTime.now());
            log.warn("文档任务结果丢弃 docId={} reason={}", task.docId(), ex.getMessage());
        } catch (RuntimeException ex) {
            recordFailure(task, ex);
        }
    }

    private void executeDelete(ClaimedTask task) {
        try {
            DocumentRepository.DocumentRow document = requireDocument(task.docId());
            pythonClient.delete(document.id());
            Files.deleteIfExists(resolveStoragePath(document.storageKey()));
            completionService.completeDelete(task.id(), document.id(), LocalDateTime.now());
            log.info("文档删除成功 docId={} attempt={}", document.id(), task.attempt());
        } catch (TaskCancelledException ex) {
            taskRepository.markCancelled(task.id(), LocalDateTime.now());
        } catch (IOException | RuntimeException ex) {
            recordFailure(task, ex);
        }
    }

    private void recordFailure(ClaimedTask task, Throwable ex) {
        String code = "TASK_FAILED";
        String message = "文档处理失败";
        if (ex instanceof AiRemoteException remote) {
            code = remote.remoteCode();
            message = remote.getMessage();
        } else if (ex instanceof BusinessException business) {
            code = business.getErrorCode() == ErrorCode.DEPENDENCY_UNAVAILABLE
                    ? "DEPENDENCY_UNAVAILABLE" : "TASK_FAILED";
            message = business.getMessage();
        } else if (ex instanceof IOException) {
            code = "STORAGE_ERROR";
            message = "文件访问失败";
        } else if (ex instanceof InvalidPathException || ex instanceof IllegalArgumentException) {
            code = "INVALID_STORAGE_PATH";
            message = "文件存储路径非法";
        }
        taskRepository.markFailure(task, code, message, LocalDateTime.now());
        log.warn("文档任务失败 docId={} type={} attempt={}/{} code={} error={}",
                task.docId(), task.type(), task.attempt(), task.maxAttempt(), code,
                ex.getClass().getSimpleName());
    }

    private DocumentRepository.DocumentRow requireDocument(long docId) {
        return documentRepository.findById(docId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在"));
    }

    private Path resolveStoragePath(String storageKey) {
        Path path = uploadRoot.resolve(storageKey).normalize();
        if (!path.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("storage key escapes upload root");
        }
        return path;
    }

    private static Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static Integer nullableInt(Object value) {
        if (value == null) {
            return null;
        }
        return number(value).intValue();
    }

    private static String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String errorType(RuntimeException ex) {
        if (ex instanceof AiRemoteException remote) {
            return remote.remoteCode();
        }
        if (ex instanceof BusinessException business) {
            return business.getErrorCode().name();
        }
        return ex.getClass().getSimpleName();
    }
}
