package com.knowbase.qa;

import com.knowbase.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 问答接口（docs/API_SPEC.md §4.1）。
 *
 * <p>kb 鉴权失败报 404/40401（不暴露存在性）；Python 不可达报 503/50301。
 */
@RestController
@RequestMapping("/qa")
public class QaController {

    private final QaService qaService;
    private final QaConversationService conversationService;

    public QaController(QaService qaService, QaConversationService conversationService) {
        this.qaService = qaService;
        this.conversationService = conversationService;
    }

    /** POST /qa/ask：question 1-2000 字，空问题 400。 */
    @PostMapping("/ask")
    public ApiResponse<QaDtos.AskResponse> ask(@Valid @RequestBody QaDtos.AskRequest request) {
        return ApiResponse.ok(qaService.ask(request));
    }

    /** POST /qa/stream: Server-Sent Events. */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody QaDtos.StreamRequest request) {
        return qaService.stream(request);
    }

    @GetMapping("/conversations")
    public ApiResponse<QaDtos.ConversationListResponse> conversations(
            @RequestParam(required = false) Long kbId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(conversationService.list(kbId, page, size));
    }

    @PostMapping("/conversations")
    public ApiResponse<QaDtos.ConversationSummary> createConversation(
            @Valid @RequestBody QaDtos.CreateConversationRequest request) {
        return ApiResponse.ok(conversationService.create(request));
    }

    @GetMapping("/conversations/{conversationId}")
    public ApiResponse<QaDtos.ConversationDetail> conversation(
            @PathVariable long conversationId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(conversationService.detail(conversationId, page, size));
    }

    @PatchMapping("/conversations/{conversationId}")
    public ApiResponse<QaDtos.ConversationSummary> renameConversation(
            @PathVariable long conversationId,
            @Valid @RequestBody QaDtos.UpdateConversationRequest request) {
        return ApiResponse.ok(conversationService.updateTitle(conversationId, request));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(@PathVariable long conversationId) {
        conversationService.delete(conversationId);
        return ApiResponse.ok(null);
    }

    @GetMapping("/citations/{citationId}")
    public ApiResponse<QaDtos.CitationSource> citation(@PathVariable long citationId) {
        return ApiResponse.ok(conversationService.citationSource(citationId));
    }
}
