package com.knowbase.workspace;

import com.knowbase.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/workspace")
public class WorkspaceController {

    private final WorkspaceSummaryService service;

    public WorkspaceController(WorkspaceSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public ApiResponse<WorkspaceSummaryService.WorkspaceSummary> summary() {
        return ApiResponse.ok(service.summary());
    }
}
