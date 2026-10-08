package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.activity.InvocationPayloadRedactor;
import com.funchole.backend.controlplane.dto.DirectFlowInvocationResponse;
import com.funchole.backend.controlplane.dto.DirectInvocationResponse;
import com.funchole.backend.controlplane.dto.InvocationInspectionResponse;
import com.funchole.backend.controlplane.dto.InvocationStepInspectionResponse;
import com.funchole.backend.controlplane.dto.InvocationStepLogResponse;
import com.funchole.backend.controlplane.service.DirectFlowInvocationCommand;
import com.funchole.backend.controlplane.service.DirectFunctionInvocationCommand;
import com.funchole.backend.controlplane.service.FlowVersionInvocationService;
import com.funchole.backend.controlplane.service.FlowVersionService;
import com.funchole.backend.controlplane.service.FunctionVersionInvocationService;
import com.funchole.backend.controlplane.service.FunctionVersionService;
import com.funchole.backend.controlplane.service.InvocationInspectionAccessService;
import com.funchole.backend.invocationcontract.DirectInvocationResult;
import com.funchole.backend.invocationcontract.FlowInvocationResult;
import com.funchole.backend.invocationcontract.InvocationInspectionResult;
import com.funchole.backend.invocationcontract.InvocationStepInspectionResult;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP tool surface for running and inspecting a direct FunctionVersion or
 * FlowVersion invocation - the same "deploy -> invoke -> inspect" loop a
 * human drives through the Test invoke/Test flow panels, exposed so an
 * agent can close that loop itself instead of needing a human to read the
 * result. get_invocation is shared by both, since one Invocation row is
 * either Flow-kind or DIRECT_FUNCTION-kind, never both.
 */
@Service
public class InvocationMcpTools {

    private final FunctionVersionService functionVersionService;
    private final FunctionVersionInvocationService functionVersionInvocationService;
    private final FlowVersionService flowVersionService;
    private final FlowVersionInvocationService flowVersionInvocationService;
    private final InvocationInspectionAccessService invocationInspectionAccessService;
    private final InvocationPayloadRedactor payloadRedactor;

    public InvocationMcpTools(
            FunctionVersionService functionVersionService,
            FunctionVersionInvocationService functionVersionInvocationService,
            FlowVersionService flowVersionService,
            FlowVersionInvocationService flowVersionInvocationService,
            InvocationInspectionAccessService invocationInspectionAccessService,
            InvocationPayloadRedactor payloadRedactor
    ) {
        this.payloadRedactor = payloadRedactor;
        this.functionVersionService = functionVersionService;
        this.functionVersionInvocationService = functionVersionInvocationService;
        this.flowVersionService = flowVersionService;
        this.flowVersionInvocationService = flowVersionInvocationService;
        this.invocationInspectionAccessService = invocationInspectionAccessService;
    }

    @McpTool(
            name = "invoke_function_version",
            description = "Directly invoke a READY FunctionVersion - no Flow or Gateway involved. Runs "
                    + "asynchronously through the real Dispatcher; poll the result with get_invocation using the "
                    + "returned invocationId."
    )
    public DirectInvocationResponse invokeFunctionVersion(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId,
            @McpToolParam(description = "Input payload as a raw JSON string, e.g. '{\"name\":\"world\"}' - defaults to '{}'", required = false) String inputPayload
    ) {
        UUID versionUuid = UUID.fromString(versionId);
        functionVersionService.getVersionById(CurrentMcpUser.id(), UUID.fromString(functionId), versionUuid);

        DirectInvocationResult result = functionVersionInvocationService.invoke(
                new DirectFunctionInvocationCommand(versionUuid, normalizePayload(inputPayload)));

        return new DirectInvocationResponse(result.invocationId(), result.functionVersionId(), result.initialStatus());
    }

    @McpTool(
            name = "invoke_flow_version",
            description = "Directly invoke a FlowVersion - no Gateway or route involved. Runs asynchronously "
                    + "through the real Dispatcher; poll the result with get_invocation using the returned "
                    + "invocationId."
    )
    public DirectFlowInvocationResponse invokeFlowVersion(
            @McpToolParam(description = "Flow id (UUID)") String flowId,
            @McpToolParam(description = "FlowVersion id (UUID)") String versionId,
            @McpToolParam(description = "Input payload as a raw JSON string, given to the flow's first step - defaults to '{}'", required = false) String inputPayload
    ) {
        UUID versionUuid = UUID.fromString(versionId);
        flowVersionService.getVersionById(CurrentMcpUser.id(), UUID.fromString(flowId), versionUuid);

        FlowInvocationResult result = flowVersionInvocationService.invoke(
                new DirectFlowInvocationCommand(versionUuid, normalizePayload(inputPayload)));

        return new DirectFlowInvocationResponse(result.invocationId(), result.flowVersionId(), result.initialStatus());
    }

    @McpTool(name = "get_invocation", description = "Inspect one Invocation's durable status, result/error, and per-step detail (including runtime logs).")
    public InvocationInspectionResponse getInvocation(@McpToolParam(description = "Invocation id (UUID)") String invocationId) {
        InvocationInspectionResult inspection =
                invocationInspectionAccessService.inspect(CurrentMcpUser.id(), UUID.fromString(invocationId));
        return toResponse(inspection);
    }

    private static String normalizePayload(String inputPayload) {
        return inputPayload == null || inputPayload.isBlank() ? "{}" : inputPayload;
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
