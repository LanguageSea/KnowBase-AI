package com.knowbase.kb;

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
@RequestMapping("/knowledge-bases/{kbId}/members")
public class KbMemberController {

    private final KbMemberService kbMemberService;

    public KbMemberController(KbMemberService kbMemberService) {
        this.kbMemberService = kbMemberService;
    }

    @GetMapping
    public ApiResponse<KbMemberDtos.MemberListResponse> list(@PathVariable long kbId) {
        return ApiResponse.ok(kbMemberService.list(kbId));
    }

    @PostMapping
    public ApiResponse<KbMemberDtos.MemberItem> grant(
            @PathVariable long kbId,
            @Valid @RequestBody KbMemberDtos.GrantRequest request) {
        return ApiResponse.ok(kbMemberService.grant(kbId, request));
    }

    @GetMapping("/candidates")
    public ApiResponse<java.util.List<KbMemberDtos.UserCandidate>> candidates(
            @PathVariable long kbId,
            @RequestParam(defaultValue = "") String keyword) {
        return ApiResponse.ok(kbMemberService.searchCandidates(kbId, keyword));
    }

    @PutMapping("/{userId}")
    public ApiResponse<KbMemberDtos.MemberItem> changeRole(
            @PathVariable long kbId,
            @PathVariable int userId,
            @Valid @RequestBody KbMemberDtos.RoleRequest request) {
        return ApiResponse.ok(kbMemberService.changeRole(kbId, userId, request));
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<Void> revoke(@PathVariable long kbId, @PathVariable int userId) {
        kbMemberService.revoke(kbId, userId);
        return ApiResponse.ok(null);
    }
}
