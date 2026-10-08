package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.activity.InvocationPayloadRedactor;
import com.funchole.backend.controlplane.dto.InvocationInspectionResponse;
import com.funchole.backend.controlplane.dto.InvocationStepInspectionResponse;
import com.funchole.backend.controlplane.dto.InvocationStepLogResponse;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.InvocationInspectionAccessService;
import com.funchole.backend.core.base.response.ApiResponse;
import com.funchole.backend.invocationcontract.InvocationInspectionResult;
import com.funchole.backend.invocationcontract.InvocationStepInspectionResult;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only inspection of one exact Invocation's durable state and
 * step-level execution detail - the same durable data the Dispatcher and
 * Runtime execution path write through, never in-memory/pending state. A
 * single call, one durable read: no waiting, polling, or re-execution.
 */
@RestController
@RequestMapping("/api/v1/invocations")
public class InvocationInspectionController {

    private final InvocationInspectionAccessService invocationInspectionAccessService;
    private final InvocationPayloadRedactor payloadRedactor;

    public InvocationInspectionController(
            InvocationInspectionAccessService invocationInspectionAccessService,
            InvocationPayloadRedactor payloadRedactor
    ) {
        this.invocationInspectionAccessService = invocationInspectionAccessService;
        this.payloadRedactor = payloadRedactor;
    }

    @GetMapping("/{invocationId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<InvocationInspectionResponse> inspect(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID invocationId
    ) {
        InvocationInspectionResult inspection = invocationInspectionAccessService.inspect(appUserPrincipal.getId(), invocationId);
        return ApiResponse.success(toResponse(inspection));
    }

    private InvocationInspectionResponse toResponse(InvocationInspectionResult inspection) {
        return new InvocationInspectionResponse(
                inspection.invocationId(),
                inspection.status(),
                inspection.flowId(),
                inspection.flowKey(),
                inspection.flowVersionId(),
                inspection.functionVersionId(),
                payloadRedactor.redact(inspection.inputPayload()),
                inspection.result(),
                inspection.error(),
                inspection.createdAt(),
                inspection.updatedAt(),
                inspection.completedAt(),
                inspection.steps().stream().map(this::toStepResponse).toList()
        );
    }

    private InvocationStepInspectionResponse toStepResponse(InvocationStepInspectionResult step) {
        return new InvocationStepInspectionResponse(
                step.stepId(),
                step.position(),
                step.componentType(),
                step.componentId(),
                step.componentVersionId(),
                step.status(),
                step.attempt(),
                step.result(),
                step.error(),
                step.createdAt(),
                step.updatedAt(),
                step.startedAt(),
                step.completedAt(),
                step.logs().stream()
                        .map(log -> new InvocationStepLogResponse(log.stream(), log.message(), log.createdAt()))
                        .toList()
        );
    }
}
