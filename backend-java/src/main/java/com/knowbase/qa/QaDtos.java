package com.knowbase.qa;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 问答 DTO（docs/API_SPEC.md §4.1）。
 *
 * <p>answer 中 [n] 必须能在 citations[].rank 找到；
 * citations[].chunkId 必须真实存在于 kb_document_chunk。
 */
public final class QaDtos {

    private QaDtos() {
    }

    /** POST /qa/ask 请求体。 */
    public record AskRequest(
            Long kbId,

            Long conversationId,

            @Size(min = 1, max = 2000, message = "问题长度必须在 1-2000 字之间")
            String question) {
    }

    /** POST /qa/stream 请求体。 */
    public record StreamRequest(
            Long kbId,
            Long conversationId,
            @Size(min = 1, max = 2000, message = "问题长度必须在 1-2000 字之间")
            String question) {
    }

    public record CreateConversationRequest(
            @NotNull(message = "kbId 不能为空")
            Long kbId,
            @Size(max = 255, message = "会话标题不能超过 255 个字符")
            String title) {
    }

    public record UpdateConversationRequest(
            @NotBlank(message = "会话标题不能为空")
            @Size(max = 255, message = "会话标题不能超过 255 个字符")
            String title) {
    }

    /** 单条引用。 */
    public record Citation(int rank, long chunkId, long docId, long kbId, String docName,
            int seq, Double score, String quote) {
    }

    /** POST /qa/ask 响应 data。 */
    public record AskResponse(long conversationId, long messageId, String answer,
            boolean noAnswer, List<Citation> citations, String chatProvider,
            String embeddingProvider, long latencyMs) {
    }

    public record ConversationSummary(long id, String title, List<Long> kbIds,
            String lastMessage, int messageCount, String createdAt, String updatedAt) {
    }

    public record ConversationListResponse(List<ConversationSummary> items, long total,
            int page, int size) {
    }

    public record CitationItem(long id, int rank, long chunkId, long docId, String docName,
            int seq, Double score, String quote, boolean accessible) {
    }

    public record MessageItem(long id, String role, String content, String status,
            String chatProvider, Integer latencyMs, String errorCode,
            String createdAt, List<CitationItem> citations) {
    }

    public record ConversationDetail(long id, String title, List<Long> kbIds,
            List<MessageItem> messages, long messageTotal, int page, int size,
            String createdAt, String updatedAt) {
    }

    public record CitationSource(long id, int rank, long chunkId, long docId,
            long kbId, String docName, int seq, Double score, String quote,
            String content) {
    }
}
