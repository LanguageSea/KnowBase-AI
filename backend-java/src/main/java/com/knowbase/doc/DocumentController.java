package com.knowbase.doc;

import java.nio.charset.StandardCharsets;

import com.knowbase.common.ApiResponse;
import com.knowbase.audit.Audit;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文档接口。所有方法都通过 AuthzService 做资源级鉴权；下载和分片预览同样重新校验。
 */
@RestController
@RequestMapping("/knowledge-bases/{kbId}/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping
    public ApiResponse<DocumentDtos.DocumentItem> upload(
            @PathVariable long kbId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(documentService.upload(kbId, file));
    }

    @GetMapping
    public ApiResponse<DocumentDtos.DocumentListResponse> list(@PathVariable long kbId) {
        return ApiResponse.ok(documentService.list(kbId));
    }

    @GetMapping("/{docId}")
    public ApiResponse<DocumentDtos.DocumentItem> get(
            @PathVariable long kbId, @PathVariable long docId) {
        return ApiResponse.ok(documentService.get(kbId, docId));
    }

    @GetMapping("/{docId}/chunks")
    public ApiResponse<DocumentDtos.ChunkListResponse> chunks(
            @PathVariable long kbId,
            @PathVariable long docId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(documentService.chunks(kbId, docId, page, size));
    }

    @GetMapping("/{docId}/chunks/{seq}")
    public ApiResponse<DocumentDtos.ChunkItem> chunk(
            @PathVariable long kbId, @PathVariable long docId, @PathVariable int seq) {
        return ApiResponse.ok(documentService.chunk(kbId, docId, seq));
    }

    @GetMapping("/{docId}/download")
    public ResponseEntity<Resource> download(@PathVariable long kbId, @PathVariable long docId) {
        DocumentService.DownloadedFile file = documentService.download(kbId, docId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.resource());
    }

    @DeleteMapping("/{docId}")
    @Audit(action = "DOC_DELETE", resourceType = "DOCUMENT", resourceIdParam = "docId")
    public ApiResponse<DocumentDtos.DocumentItem> delete(
            @PathVariable long kbId, @PathVariable long docId) {
        return ApiResponse.ok(documentService.delete(kbId, docId));
    }

    @PostMapping("/{docId}/retry")
    public ApiResponse<DocumentDtos.DocumentItem> retry(
            @PathVariable long kbId, @PathVariable long docId) {
        return ApiResponse.ok(documentService.retry(kbId, docId));
    }
}
