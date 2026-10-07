package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.dto.FlowStepResponse;
import com.funchole.backend.controlplane.dto.FlowPublishRequest;
import com.funchole.backend.controlplane.dto.FlowVersionCreateRequest;
import com.funchole.backend.controlplane.dto.FlowVersionResponse;
import com.funchole.backend.controlplane.entity.FlowStep;
import com.funchole.backend.controlplane.entity.FlowVersion;
import com.funchole.backend.controlplane.mapper.FlowStepMapper;
import com.funchole.backend.controlplane.mapper.FlowVersionMapper;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.FlowStepService;
import com.funchole.backend.controlplane.service.FlowPublicationService;
import com.funchole.backend.controlplane.service.FlowVersionService;
import com.funchole.backend.core.base.mapper.PaginationMapper;
import com.funchole.backend.core.base.response.ApiResponse;
import com.funchole.backend.core.base.response.PaginationResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/flows/{flowId}/versions")
public class FlowVersionController {
    private final FlowVersionService flowVersionService;
    private final FlowPublicationService flowPublicationService;
    private final FlowStepService flowStepService;
    private final FlowVersionMapper flowVersionMapper;
    private final FlowStepMapper flowStepMapper;
    private final PaginationMapper paginationMapper;

    public FlowVersionController(
            FlowVersionService flowVersionService,
            FlowPublicationService flowPublicationService,
            FlowStepService flowStepService,
            FlowVersionMapper flowVersionMapper,
            FlowStepMapper flowStepMapper,
            PaginationMapper paginationMapper
    ) {
        this.flowVersionService = flowVersionService;
        this.flowPublicationService = flowPublicationService;
        this.flowStepService = flowStepService;
        this.flowVersionMapper = flowVersionMapper;
        this.flowStepMapper = flowStepMapper;
        this.paginationMapper = paginationMapper;
    }

    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<PaginationResponse<FlowVersionResponse>> listVersions(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<FlowVersionResponse> versions = flowVersionService.listVersions(appUserPrincipal.getId(), flowId, page, size)
                .map(flowVersionMapper::toResponse);
        return ApiResponse.success(paginationMapper.toResponse(versions));
    }

    @GetMapping("/{versionId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FlowVersionResponse> getVersionById(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @PathVariable UUID versionId
    ) {
        FlowVersion flowVersion = flowVersionService.getVersionById(appUserPrincipal.getId(), flowId, versionId);
        return ApiResponse.success(toResponseWithSteps(appUserPrincipal, flowId, flowVersion));
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FlowVersionResponse> createDraftVersion(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @Valid @RequestBody FlowVersionCreateRequest request
    ) {
        FlowVersion flowVersion = flowVersionService.createDraftVersion(appUserPrincipal.getId(), flowId, request);
        return ApiResponse.success(flowVersionMapper.toResponse(flowVersion));
    }

    @PostMapping("/{versionId}/adopt")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FlowVersionResponse> adoptVersion(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @PathVariable UUID versionId
    ) {
        FlowVersion flowVersion = flowPublicationService.adoptWithoutExpectation(appUserPrincipal.getId(), flowId, versionId);
        return ApiResponse.success(flowVersionMapper.toResponse(flowVersion));
    }

    @PostMapping("/{versionId}/publish")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FlowVersionResponse> publishVersion(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @PathVariable UUID versionId,
            @Valid @RequestBody FlowPublishRequest request
    ) {
        FlowVersion published = flowPublicationService.publish(appUserPrincipal.getId(), flowId, versionId,
                request.expectedActiveVersionId(), request.route());
        return ApiResponse.success(flowVersionMapper.toResponse(published));
    }

    @PostMapping("/{versionId}/archive")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<FlowVersionResponse> archiveVersion(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @PathVariable UUID versionId
    ) {
        FlowVersion flowVersion = flowVersionService.archiveVersion(appUserPrincipal.getId(), flowId, versionId);
        return ApiResponse.success(flowVersionMapper.toResponse(flowVersion));
    }

    @DeleteMapping("/{versionId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Map<String, String>> deleteVersion(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID flowId,
            @PathVariable UUID versionId
    ) {
        flowVersionService.deleteDraftVersion(appUserPrincipal.getId(), flowId, versionId);
        return ApiResponse.success(Map.of("message", "Flow version deleted successfully"));
    }

    private FlowVersionResponse toResponseWithSteps(AppUserPrincipal appUserPrincipal, UUID flowId, FlowVersion flowVersion) {
        FlowVersionResponse base = flowVersionMapper.toResponse(flowVersion);
        List<FlowStep> steps = flowStepService.listSteps(appUserPrincipal.getId(), flowId, flowVersion.getId());
        List<FlowStepResponse> stepResponses = steps.stream().map(flowStepMapper::toResponse).toList();
        return new FlowVersionResponse(
                base.id(),
                base.flowId(),
                base.version(),
                base.status(),
                base.runtime(),
                base.metadata(),
                stepResponses,
                base.createdAt(),
                base.updatedAt(),
                base.adoptedAt(),
                base.archivedAt()
        );
    }
}
