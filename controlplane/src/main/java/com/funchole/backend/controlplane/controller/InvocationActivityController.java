package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.activity.InvocationActivityService;
import com.funchole.backend.controlplane.dto.ActivitySummaryResponse;
import com.funchole.backend.controlplane.dto.InvocationActivityResponse;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.core.base.response.ApiResponse;
import com.funchole.backend.core.base.response.PaginationResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's recent requests (and a 24-hour summary). Read-only, scoped to
 * the caller, and free of request payloads; open one request through
 * {@code GET /api/v1/invocations/{id}} for its detail.
 */
@RestController
@RequestMapping("/api/v1/invocations")
public class InvocationActivityController {

    private final InvocationActivityService activityService;

    public InvocationActivityController(InvocationActivityService activityService) {
        this.activityService = activityService;
    }

    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<PaginationResponse<InvocationActivityResponse>> list(
            @AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) UUID flowId
    ) {
        return ApiResponse.success(activityService.list(principal.getId(), source, flowId, page, size));
    }

    @GetMapping("/summary")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<ActivitySummaryResponse> summary(@AuthenticationPrincipal AppUserPrincipal principal) {
        return ApiResponse.success(activityService.summary(principal.getId()));
    }
}
