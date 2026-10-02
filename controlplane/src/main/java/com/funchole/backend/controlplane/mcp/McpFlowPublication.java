package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.dto.FlowUpdateRequest;
import com.funchole.backend.controlplane.dto.FlowVersionResponse;
import com.funchole.backend.controlplane.entity.Flow;
import com.funchole.backend.controlplane.service.FlowService;
import com.funchole.backend.controlplane.service.FlowVersionService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MCP-local relational transactions. External build and secret writes are intentionally separate. */
@Service
public class McpFlowPublication {
    private final FlowService flowService;
    private final FlowVersionService versionService;
    private final FlowMcpTools flows;
    private final FlowVersionMcpTools versions;
    private final EntityManager entityManager;

    public McpFlowPublication(FlowService flowService, FlowVersionService versionService,
                              FlowMcpTools flows, FlowVersionMcpTools versions, EntityManager entityManager) {
        this.flowService = flowService;
        this.versionService = versionService;
        this.flows = flows;
        this.versions = versions;
        this.entityManager = entityManager;
    }

    @Transactional(rollbackFor = Exception.class)
    public FlowVersionResponse compose(McpWorkflowTools.ComposeRequest request, UUID flowId, UUID gatewayId,
                                        String runtime, List<McpWorkflowTools.StepInput> steps) throws Exception {
        if (flowId == null) flowId = flows.createFlow(request.key(), request.name(), null, gatewayId.toString(),
                request.httpMethod(), request.path(), request.priority()).id();
        FlowVersionResponse draft = versions.createFlowVersion(flowId.toString(), runtime, null);
        for (int i = 0; i < steps.size(); i++) {
            McpWorkflowTools.StepInput step = steps.get(i);
            McpReference ref = McpReference.parse(step.reference());
            versions.createFlowStep(flowId.toString(), draft.id().toString(), step.key(), step.type().name(), i + 1,
                    ref.parentId().toString(), ref.id().toString(), step.metadata());
        }
        return versions.getFlowVersion(flowId.toString(), draft.id().toString());
    }

    @Transactional(rollbackFor = Exception.class)
    public McpOperationResult publish(McpReference target, UUID expected, McpWorkflowTools.RouteInput route, UUID gatewayId) {
        UUID userId = CurrentMcpUser.id();
        // Ownership is checked before acquiring the row lock. Refresh reads active state only AFTER the lock.
        Flow flow = flowService.getFlowById(userId, target.parentId());
        entityManager.refresh(flow, LockModeType.PESSIMISTIC_WRITE);
        flow = flowService.getFlowById(userId, target.parentId());
        if (!Objects.equals(flow.getActiveFlowVersionId(), expected)) {
            return McpOperationResult.failure("CONFLICT", "The active revision changed. Read current state before publishing again.",
                    McpReference.version("flow-versions", target.parentId(), target.id()));
        }
        // Adoption validates the draft and references again. Route writes share this transaction and rollback.
        versionService.adoptVersion(userId, target.parentId(), target.id());
        if (route != null) flowService.updateFlow(userId, flow.getId(), new FlowUpdateRequest(
                flow.getName(), flow.getDescription(), gatewayId, route.httpMethod(), route.path(),
                route.priority() == null ? flow.getPriority() : route.priority()));
        FlowVersionResponse receipt = versions.getFlowVersion(target.parentId().toString(), target.id().toString());
        String ref = McpReference.version("flow-versions", target.parentId(), target.id());
        return new McpOperationResult(true, "OK", "Revision adopted. External HTTPS remains unverified.", ref, receipt,
                Map.of("state", ref, "flow", McpReference.of("flows", target.parentId()), "guide", "funchole://guides/flows"),
                List.of("MCP publication calls are serialized; REST writers do not participate in this guard."));
    }
}
