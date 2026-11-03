package com.knowbase.doc;

import java.util.List;

/**
 * 文档接口 DTO。document.status 使用 DDL 状态机；taskStatus 单独暴露队列状态，
 * 前端可把 PENDING 显示为“排队中”、ACTIVE 显示为“成功”。
 */
public final class DocumentDtos {

    private DocumentDtos() {
    }

    public record DocumentItem(long id, long kbId, String name, String ext, long sizeBytes,
            String sha256, String storageKey, String status, int chunkCount, String indexedAt,
            String errorCode, String errorMsg, String taskStatus, int attempt, int maxAttempt,
            boolean canRetry, String createdAt) {
    }

    public record DocumentListItem(long id, String name, String ext, long sizeBytes,
            String status, int chunkCount, String taskStatus, String errorCode,
            String errorMsg, int attempt, int maxAttempt, boolean canRetry,
            String createdAt) {
    }

    public record DocumentListResponse(List<DocumentListItem> items, long total) {
    }

    public record ChunkItem(long id, int seq, String content, Integer pageNo, String heading,
            Integer tokenCount, String createdAt) {
    }

    public record ChunkListResponse(long docId, List<ChunkItem> items, long total,
            int page, int size) {
    }
}
