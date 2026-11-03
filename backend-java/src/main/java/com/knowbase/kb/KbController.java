package com.knowbase.kb;

import com.knowbase.common.ApiResponse;
import com.knowbase.audit.Audit;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库接口（docs/API_SPEC.md §2）。
 *
 * <p>全部需要认证（AuthFilter 已拦）；响应走统一 ApiResponse。
 */
@RestController
@RequestMapping("/knowledge-bases")
public class KbController {

    private final KbService kbService;

    public KbController(KbService kbService) {
        this.kbService = kbService;
    }

    /** GET /knowledge-bases：只返回当前用户 ACTIVE 库。 */
    @GetMapping
    public ApiResponse<KbDtos.KbListResponse> list() {
        return ApiResponse.ok(kbService.list());
    }

    /** POST /knowledge-bases：同 owner 重名 409。 */
    @PostMapping
    @Audit(action = "KB_CREATE", resourceType = "KB")
    public ApiResponse<KbDtos.KbItem> create(@Valid @RequestBody KbDtos.CreateRequest request) {
        return ApiResponse.ok(kbService.create(request));
    }

    /** GET /knowledge-bases/{kbId}：非 owner 404，不暴露存在性。 */
    @GetMapping("/{kbId}")
    public ApiResponse<KbDtos.KbItem> get(@PathVariable long kbId) {
        return ApiResponse.ok(kbService.get(kbId));
    }
}
