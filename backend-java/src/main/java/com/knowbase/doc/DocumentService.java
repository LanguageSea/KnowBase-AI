package com.knowbase.doc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.knowbase.auth.AuthUser;
import com.knowbase.auth.CurrentUser;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.config.StorageProperties;
import com.knowbase.ingest.IngestionTaskRepository;
import com.knowbase.ingest.IngestionTaskRepository.TaskSnapshot;
import com.knowbase.ingest.IngestionTaskRepository.TaskType;
import com.knowbase.kb.AuthzService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档领域服务。上传只做鉴权、格式/大小校验、防路径穿越落盘和任务入队，
 * Python 解析与索引由 IngestionWorker 异步执行。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("txt", "md", "pdf", "docx");
    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final int MAX_PDF_PAGES = 300;
    private static final int DEFAULT_MAX_ATTEMPT = 3;
    private static final int DELETE_MAX_ATTEMPT = 5;
    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy/MM");

    private final AuthzService authzService;
    private final DocumentRepository documentRepository;
    private final IngestionTaskRepository taskRepository;
    private final Path uploadRoot;

    public DocumentService(AuthzService authzService, DocumentRepository documentRepository,
            IngestionTaskRepository taskRepository, StorageProperties storageProperties) {
        this.authzService = authzService;
        this.documentRepository = documentRepository;
        this.taskRepository = taskRepository;
        String dir = storageProperties.uploadDir();
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("knowbase.storage.upload-dir 未配置");
        }
        this.uploadRoot = Path.of(dir.trim()).toAbsolutePath().normalize();
    }

    public DocumentDtos.DocumentListResponse list(long kbId) {
        authzService.requireReadKb(kbId);
        List<DocumentDtos.DocumentListItem> items = documentRepository.listByKb(kbId).stream()
                .map(this::toListItem)
                .toList();
        return new DocumentDtos.DocumentListResponse(items, items.size());
    }

    public DocumentDtos.DocumentItem get(long kbId, long docId) {
        authzService.requireReadKb(kbId);
        return toItem(requireDocumentInKb(kbId, docId));
    }

    /**
     * 保存到 UUID 路径并入队。multipart 已完整接收后才进入此方法；临时文件只在
     * 数据库事务成功前存在，避免调试重启留下半截正式文件。
     */
    @Transactional
    public DocumentDtos.DocumentItem upload(long kbId, MultipartFile file) {
        authzService.requireWriteKb(kbId);
        AuthUser user = CurrentUser.require();
        byte[] bytes = validateAndRead(file);
        String originalName = safeDisplayName(file.getOriginalFilename());
        String ext = extractExt(originalName);
        validatePdf(bytes, ext);

        String sha256 = sha256(bytes);
        LocalDateTime now = LocalDateTime.now();
        String storageKey = now.format(YEAR_MONTH) + "/" + UUID.randomUUID() + "." + ext;
        Path target = resolveStoragePath(storageKey);
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        try {
            Files.createDirectories(target.getParent());
            Files.write(temp, bytes);
            moveAtomically(temp, target);
            long docId = documentRepository.insertPending(
                    kbId, originalName, ext, bytes.length, sha256, storageKey, user.id(), now);
            taskRepository.insertQueued(docId, TaskType.INGEST, DEFAULT_MAX_ATTEMPT, now);
            log.info("文档已入队 docId={} kbId={} ext={} size={}",
                    docId, kbId, ext, bytes.length);
            return toItem(documentRepository.findById(docId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "文档写入后查询失败")));
        } catch (DuplicateKeyException ex) {
            deleteQuietly(temp);
            deleteQuietly(target);
            throw new BusinessException(ErrorCode.CONFLICT, "该文件已在知识库中存在");
        } catch (IOException ex) {
            deleteQuietly(temp);
            deleteQuietly(target);
            log.warn("文档落盘失败 kbId={} error={}", kbId, ex.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件保存失败");
        } catch (RuntimeException ex) {
            deleteQuietly(temp);
            deleteQuietly(target);
            throw ex;
        }
    }

    @Transactional
    public DocumentDtos.DocumentItem retry(long kbId, long docId) {
        authzService.requireWriteKb(kbId);
        DocumentRepository.DocumentRow document = requireDocumentInKb(kbId, docId);
        LocalDateTime now = LocalDateTime.now();
        if ("FAILED".equals(document.status())) {
            TaskSnapshot task = taskRepository.findLatest(docId, TaskType.INGEST)
                    .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "没有可重试的解析任务"));
            if (!"FAILED".equals(task.status()) || task.attempt() >= task.maxAttempt()) {
                throw new BusinessException(ErrorCode.CONFLICT, "任务已耗尽重试次数，请联系管理员");
            }
            if (!taskRepository.requeueForRetry(docId, TaskType.INGEST, now)) {
                throw new BusinessException(ErrorCode.CONFLICT, "任务状态已变化，请刷新后重试");
            }
            documentRepository.clearFailureForRetry(docId, now);
        } else if ("DELETE_FAILED".equals(document.status())) {
            retryDeleteTask(docId, now);
            documentRepository.markDeleting(docId, now);
        } else {
            throw new BusinessException(ErrorCode.CONFLICT, "当前状态不允许重试");
        }
        return toItem(requireDocumentInKb(kbId, docId));
    }

    @Transactional
    public DocumentDtos.DocumentItem delete(long kbId, long docId) {
        authzService.requireWriteKb(kbId);
        DocumentRepository.DocumentRow document = requireDocumentInKb(kbId, docId);
        if ("DELETED".equals(document.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        if ("DELETING".equals(document.status())) {
            return toItem(document);
        }
        LocalDateTime now = LocalDateTime.now();
        if (!"DELETE_FAILED".equals(document.status()) && documentRepository.markDeleting(docId, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "文档状态已变化，请刷新后重试");
        }
        taskRepository.cancelQueuedOrRunning(docId, now);
        if ("DELETE_FAILED".equals(document.status())) {
            retryDeleteTask(docId, now);
            documentRepository.markDeleting(docId, now);
        } else {
            taskRepository.insertQueued(docId, TaskType.DELETE, DELETE_MAX_ATTEMPT, now);
        }
        return toItem(requireDocumentInKb(kbId, docId));
    }

    public DocumentDtos.ChunkListResponse chunks(long kbId, long docId, int page, int size) {
        authzService.requireReadKb(kbId);
        requireDocumentInKb(kbId, docId);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        List<DocumentRepository.ChunkSnapshot> all = documentRepository.listChunks(docId);
        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<DocumentDtos.ChunkItem> items = all.subList(from, to).stream()
                .map(this::toChunk)
                .toList();
        return new DocumentDtos.ChunkListResponse(docId, items, all.size(), safePage, safeSize);
    }

    public DocumentDtos.ChunkItem chunk(long kbId, long docId, int seq) {
        authzService.requireReadKb(kbId);
        requireDocumentInKb(kbId, docId);
        return documentRepository.findChunk(docId, seq)
                .map(this::toChunk)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "分片不存在"));
    }

    public DownloadedFile download(long kbId, long docId) {
        authzService.requireReadKb(kbId);
        DocumentRepository.DocumentRow document = requireDocumentInKb(kbId, docId);
        Path path = resolveStoragePath(document.storageKey());
        if (!Files.isRegularFile(path)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "原始文件不存在");
        }
        Resource resource = new FileSystemResource(path);
        return new DownloadedFile(document.name(), resource);
    }

    private void retryDeleteTask(long docId, LocalDateTime now) {
        TaskSnapshot task = taskRepository.findLatest(docId, TaskType.DELETE)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "没有可重试的删除任务"));
        if (!"FAILED".equals(task.status()) || task.attempt() >= task.maxAttempt()) {
            throw new BusinessException(ErrorCode.CONFLICT, "删除任务已耗尽重试次数，请联系管理员");
        }
        if (!taskRepository.requeueForRetry(docId, TaskType.DELETE, now)) {
            throw new BusinessException(ErrorCode.CONFLICT, "删除任务状态已变化，请刷新后重试");
        }
    }

    private byte[] validateAndRead(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "上传文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文件超过 20MB 上限");
        }
        String ext = extractExt(safeDisplayName(file.getOriginalFilename()));
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "仅支持 TXT、MD、PDF、DOCX 文件");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败");
        }
    }

    private static void validatePdf(byte[] bytes, String ext) {
        if (!"pdf".equals(ext)) {
            return;
        }
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "PDF 超过 300 页上限");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "PDF 文件损坏或格式不受支持");
        }
    }

    private DocumentRepository.DocumentRow requireDocumentInKb(long kbId, long docId) {
        DocumentRepository.DocumentRow document = documentRepository.findById(docId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在"));
        if (document.kbId() != kbId || "DELETED".equals(document.status())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        return document;
    }

    private DocumentDtos.DocumentItem toItem(DocumentRepository.DocumentRow row) {
        TaskSnapshot task = latestTask(row);
        return new DocumentDtos.DocumentItem(
                row.id(), row.kbId(), row.name(), row.ext(), row.sizeBytes(), row.sha256(),
                row.storageKey(), row.status(), row.chunkCount(), row.indexedAt(),
                row.errorCode(), row.errorMsg(), taskStatus(row, task),
                task == null ? 0 : task.attempt(), task == null ? 0 : task.maxAttempt(),
                canRetry(row, task), row.createdAt());
    }

    private DocumentDtos.DocumentListItem toListItem(DocumentRepository.DocumentRow row) {
        TaskSnapshot task = latestTask(row);
        return new DocumentDtos.DocumentListItem(
                row.id(), row.name(), row.ext(), row.sizeBytes(), row.status(), row.chunkCount(),
                taskStatus(row, task), row.errorCode(), row.errorMsg(),
                task == null ? 0 : task.attempt(), task == null ? 0 : task.maxAttempt(),
                canRetry(row, task), row.createdAt());
    }

    private TaskSnapshot latestTask(DocumentRepository.DocumentRow row) {
        TaskType type = "DELETING".equals(row.status()) || "DELETE_FAILED".equals(row.status())
                ? TaskType.DELETE : TaskType.INGEST;
        return taskRepository.findLatest(row.id(), type).orElse(null);
    }

    private static boolean canRetry(DocumentRepository.DocumentRow row, TaskSnapshot task) {
        if (task == null || !"FAILED".equals(task.status()) || task.attempt() >= task.maxAttempt()) {
            return false;
        }
        return "FAILED".equals(row.status()) || "DELETE_FAILED".equals(row.status());
    }

    private static String taskStatus(DocumentRepository.DocumentRow row, TaskSnapshot task) {
        if (task != null) {
            return task.status();
        }
        return switch (row.status()) {
            case "PENDING" -> "QUEUED";
            case "PROCESSING" -> "RUNNING";
            case "ACTIVE" -> "SUCCESS";
            case "DELETED" -> "SUCCESS";
            default -> row.status();
        };
    }

    private DocumentDtos.ChunkItem toChunk(DocumentRepository.ChunkSnapshot row) {
        return new DocumentDtos.ChunkItem(
                row.id(), row.seq(), row.content(), row.pageNo(), row.heading(),
                row.tokenCount(), row.createdAt());
    }

    private Path resolveStoragePath(String storageKey) {
        Path path = uploadRoot.resolve(storageKey).normalize();
        if (!path.startsWith(uploadRoot)) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "存储路径非法");
        }
        return path;
    }

    private static String safeDisplayName(String raw) {
        String value = raw == null || raw.isBlank() ? "document.txt" : raw.trim();
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        value = value.replaceAll("[\\p{Cntrl}]", "_");
        if (value.isBlank()) {
            value = "document.txt";
        }
        if (value.length() > 255) {
            String ext = extractExt(value);
            int suffix = ext.isEmpty() ? 0 : ext.length() + 1;
            value = value.substring(0, 255 - suffix) + (suffix == 0 ? "" : "." + ext);
        }
        return value;
    }

    private static String extractExt(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return "";
        }
        return filename.substring(dot + 1).toLowerCase();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", ex);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败由孤儿文件扫描兜底。
        }
    }

    public record DownloadedFile(String filename, Resource resource) {
    }
}
