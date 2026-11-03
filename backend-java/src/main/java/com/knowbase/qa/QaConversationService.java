package com.knowbase.qa;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.knowbase.auth.AuthUser;
import com.knowbase.auth.CurrentUser;
import com.knowbase.common.BusinessException;
import com.knowbase.common.ErrorCode;
import com.knowbase.doc.DocumentRepository;
import com.knowbase.kb.AuthzService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Conversation CRUD and citation re-authorisation. Another user's conversation
 * always resolves to 404 so ids cannot be used to enumerate private resources.
 */
@Service
public class QaConversationService {

    private static final int MAX_PAGE_SIZE = 100;

    private final QaRepository repository;
    private final AuthzService authzService;
    private final DocumentRepository documentRepository;

    public QaConversationService(QaRepository repository, AuthzService authzService,
            DocumentRepository documentRepository) {
        this.repository = repository;
        this.authzService = authzService;
        this.documentRepository = documentRepository;
    }

    @Transactional
    public QaDtos.ConversationSummary create(QaDtos.CreateConversationRequest request) {
        AuthUser user = CurrentUser.require();
        authzService.requireReadKb(request.kbId());
        LocalDateTime now = LocalDateTime.now();
        long id = repository.insertConversation(user.id(), normalizeTitle(request.title()), now);
        repository.insertConversationKb(id, request.kbId(), now);
        QaRepository.ConversationRow row = requireConversation(id, user.id());
        return toSummary(row, List.of(request.kbId()), 0, "");
    }

    public QaDtos.ConversationListResponse list(Long kbId, int page, int size) {
        AuthUser user = CurrentUser.require();
        if (kbId != null) {
            authzService.requireReadKb(kbId);
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int offset = (safePage - 1) * safeSize;
        List<QaDtos.ConversationSummary> items = repository
                .listConversations(user.id(), kbId, offset, safeSize)
                .stream()
                .map(row -> toSummary(
                        new QaRepository.ConversationRow(
                                row.id(), user.id(), row.title(), "ACTIVE",
                                row.createdAt(), row.updatedAt()),
                        repository.findConversationKbIds(row.id()),
                        row.messageCount(),
                        row.lastMessage()))
                .toList();
        return new QaDtos.ConversationListResponse(
                items, repository.countConversations(user.id(), kbId), safePage, safeSize);
    }

    public QaDtos.ConversationDetail detail(long conversationId, int page, int size) {
        AuthUser user = CurrentUser.require();
        QaRepository.ConversationRow conversation = requireConversation(conversationId, user.id());
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int offset = (safePage - 1) * safeSize;
        Set<Long> readableKbs = new HashSet<>(authzService.readableKbIds(user.id()));
        List<QaDtos.MessageItem> messages = repository
                .listMessages(conversationId, offset, safeSize)
                .stream()
                .map(message -> toMessage(message, readableKbs))
                .toList();
        return new QaDtos.ConversationDetail(
                conversation.id(),
                conversation.title(),
                repository.findConversationKbIds(conversationId),
                messages,
                repository.countMessages(conversationId),
                safePage,
                safeSize,
                conversation.createdAt(),
                conversation.updatedAt());
    }

    public QaDtos.ConversationSummary updateTitle(
            long conversationId, QaDtos.UpdateConversationRequest request) {
        AuthUser user = CurrentUser.require();
        requireConversation(conversationId, user.id());
        if (!repository.updateConversationTitle(
                conversationId, user.id(), request.title().trim(), LocalDateTime.now())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        QaRepository.ConversationRow row = requireConversation(conversationId, user.id());
        return toSummary(
                row,
                repository.findConversationKbIds(conversationId),
                (int) repository.countMessages(conversationId),
                "");
    }

    @Transactional
    public void delete(long conversationId) {
        AuthUser user = CurrentUser.require();
        requireConversation(conversationId, user.id());
        repository.deleteConversation(conversationId, user.id());
    }

    /**
     * Explicit citation endpoint. It deliberately reads the current chunk
     * content after re-checking that the document is still ACTIVE and readable.
     */
    public QaDtos.CitationSource citationSource(long citationId) {
        AuthUser user = CurrentUser.require();
        QaRepository.CitationRow citation = repository.findCitationForUser(citationId, user.id());
        if (citation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "引用不存在");
        }
        authzService.requireReadKb(citation.kbId());
        if (!"ACTIVE".equals(citation.docStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "引用原文已不可用");
        }
        DocumentRepository.ChunkSnapshot chunk = documentRepository
                .findChunk(citation.docId(), citation.seq())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "引用分片不存在"));
        return new QaDtos.CitationSource(
                citation.id(),
                citation.rankNo(),
                citation.chunkId(),
                citation.docId(),
                citation.kbId(),
                citation.docName(),
                chunk.seq(),
                citation.score(),
                citation.quote(),
                chunk.content());
    }

    public QaRepository.ConversationRow requireConversation(long conversationId, int userId) {
        QaRepository.ConversationRow row = repository.findConversation(conversationId, userId);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return row;
    }

    private QaDtos.MessageItem toMessage(
            QaRepository.MessageRow message, Set<Long> readableKbs) {
        List<QaDtos.CitationItem> citations = new ArrayList<>();
        for (QaRepository.CitationRow citation : repository.listCitations(message.id())) {
            boolean accessible = readableKbs.contains(citation.kbId())
                    && "ACTIVE".equals(citation.docStatus());
            citations.add(new QaDtos.CitationItem(
                    citation.id(),
                    citation.rankNo(),
                    citation.chunkId(),
                    citation.docId(),
                    citation.docName(),
                    citation.seq(),
                    citation.score(),
                    accessible ? citation.quote() : null,
                    accessible));
        }
        return new QaDtos.MessageItem(
                message.id(),
                message.role(),
                message.content(),
                message.status(),
                message.modelName(),
                message.latencyMs(),
                message.errorCode(),
                message.createdAt(),
                citations);
    }

    private static QaDtos.ConversationSummary toSummary(
            QaRepository.ConversationRow row,
            List<Long> kbIds,
            int messageCount,
            String lastMessage) {
        return new QaDtos.ConversationSummary(
                row.id(), row.title(), List.copyOf(kbIds), lastMessage,
                messageCount, row.createdAt(), row.updatedAt());
    }

    private static String normalizeTitle(String title) {
        String value = title == null ? "" : title.trim();
        return value.isEmpty() ? "新对话" : value;
    }
}
