package com.knowbase.model;

import com.knowbase.audit.Audit;
import com.knowbase.auth.Permission;
import com.knowbase.auth.RequirePermission;
import com.knowbase.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/models")
@RequirePermission(Permission.MODEL_MANAGE)
public class ModelConfigController {

    private final ModelConfigService modelConfigService;
    private final AiCallLogService callLogService;

    public ModelConfigController(ModelConfigService modelConfigService,
            AiCallLogService callLogService) {
        this.modelConfigService = modelConfigService;
        this.callLogService = callLogService;
    }

    @GetMapping
    public ApiResponse<ModelConfigDtos.ModelListResponse> list() {
        return ApiResponse.ok(modelConfigService.list());
    }

    @GetMapping("/{modelId}")
    public ApiResponse<ModelConfigDtos.ModelItem> get(@PathVariable long modelId) {
        return ApiResponse.ok(modelConfigService.get(modelId));
    }

    @PostMapping
    @Audit(action = "MODEL_CREATE", resourceType = "MODEL")
    public ApiResponse<ModelConfigDtos.ModelItem> create(
            @Valid @RequestBody ModelConfigDtos.CreateRequest request) {
        return ApiResponse.ok(modelConfigService.create(request));
    }

    @PutMapping("/{modelId}")
    @Audit(action = "MODEL_UPDATE", resourceType = "MODEL", resourceIdParam = "modelId")
    public ApiResponse<ModelConfigDtos.ModelItem> update(@PathVariable long modelId,
            @Valid @RequestBody ModelConfigDtos.UpdateRequest request) {
        return ApiResponse.ok(modelConfigService.update(modelId, request));
    }

    @DeleteMapping("/{modelId}")
    @Audit(action = "MODEL_DELETE", resourceType = "MODEL", resourceIdParam = "modelId")
    public ApiResponse<Void> delete(@PathVariable long modelId) {
        modelConfigService.delete(modelId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{modelId}/test")
    @Audit(action = "MODEL_TEST", resourceType = "MODEL", resourceIdParam = "modelId")
    public ApiResponse<ModelConfigDtos.TestResponse> test(@PathVariable long modelId) {
        return ApiResponse.ok(modelConfigService.test(modelId));
    }

    @GetMapping("/stats")
    public ApiResponse<AiCallLogService.ModelStatsResponse> stats(
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(callLogService.stats(days));
    }
}
