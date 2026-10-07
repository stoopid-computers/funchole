package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.dto.FlowVersionResponse;
import com.funchole.backend.controlplane.service.FlowPublicationConflictException;
import com.funchole.backend.controlplane.service.FlowPublicationService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Maps the transport-neutral publication result to an MCP receipt. */
@Service
public class McpFlowPublication {
    private final FlowPublicationService publicationService;
    private final FlowVersionMcpTools versions;

    public McpFlowPublication(FlowPublicationService publicationService, FlowVersionMcpTools versions) {
        this.publicationService = publicationService;
        this.versions = versions;
    }

    public McpOperationResult publish(McpReference target, UUID expected, McpWorkflowTools.RouteInput route, UUID gatewayId) {
        UUID userId = CurrentMcpUser.id();
        try {
            if (route == null) {
                publicationService.publish(userId, target.parentId(), target.id(), expected, null);
            } else {
                publicationService.publishRoute(userId, target.parentId(), target.id(), expected,
                        gatewayId, route.httpMethod(), route.path(), route.priority());
            }
        } catch (FlowPublicationConflictException conflict) {
            return McpOperationResult.failure("CONFLICT", "The active revision changed. Read current state before publishing again.",
                    McpReference.version("flow-versions", target.parentId(), target.id()));
        }
        FlowVersionResponse receipt = versions.getFlowVersion(target.parentId().toString(), target.id().toString());
        String ref = McpReference.version("flow-versions", target.parentId(), target.id());
        return new McpOperationResult(true, "OK", "Revision adopted. External HTTPS remains unverified.", ref, receipt,
                Map.of("state", ref, "flow", McpReference.of("flows", target.parentId()), "guide", "funchole://guides/flows"),
                List.of());
    }
}
