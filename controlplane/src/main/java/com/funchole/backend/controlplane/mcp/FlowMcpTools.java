package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.dto.FlowCreateRequest;
import com.funchole.backend.controlplane.dto.FlowResponse;
import com.funchole.backend.controlplane.dto.FlowUpdateRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Flow;
import com.funchole.backend.controlplane.mapper.FlowMapper;
import com.funchole.backend.controlplane.service.FlowService;
import com.funchole.backend.controlplane.service.ProfileService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * MCP tool surface for Flows - mirrors {@code FlowController} exactly. A
 * Flow has a Flow Route (method+path under a Gateway); its FlowVersions compose
 * Function/RESPONSE/MIDDLEWARE/SUB_FLOW steps - see FlowVersion/FlowStep
 * tools for the composition itself.
 */
@Service
public class FlowMcpTools {

    private final FlowService flowService;
    private final ProfileService profileService;
    private final FlowMapper flowMapper;

    public FlowMcpTools(FlowService flowService, ProfileService profileService, FlowMapper flowMapper) {
        this.flowService = flowService;
        this.profileService = profileService;
        this.flowMapper = flowMapper;
    }

    @McpTool(name = "list_flows", description = "List the current user's Flows, most recently created first.")
    public List<FlowResponse> listFlows(
            @McpToolParam(description = "1-based page number, defaults to 1", required = false) Integer page,
            @McpToolParam(description = "Page size, defaults to 20", required = false) Integer size
    ) {
        Page<FlowResponse> result = flowService
                .listFlows(CurrentMcpUser.id(), page != null ? page : 1, size != null ? size : 20)
                .map(flowMapper::toResponse);
        return result.getContent();
    }

    @McpTool(name = "get_flow", description = "Get one Flow by id.")
    public FlowResponse getFlow(@McpToolParam(description = "Flow id (UUID)") String flowId) {
        Flow flow = flowService.getFlowById(CurrentMcpUser.id(), UUID.fromString(flowId));
        return flowMapper.toResponse(flow);
    }

    @McpTool(
            name = "create_flow",
            description = "Create a Flow identity and its Flow Route (method+path under a Gateway). Create a "
                    + "matching runtime Flow Version, add pinned steps, test, then adopt to serve live HTTP. "
                    + "Read get_funchole_guide('flows') before routing."
    )
    public FlowResponse createFlow(
            @McpToolParam(description = "Unique key: letters, numbers, '_', '.' and '-' only, e.g. flw_orders_list") String flowKey,
            @McpToolParam(description = "Display name") String name,
            @McpToolParam(description = "Description", required = false) String description,
            @McpToolParam(description = "Gateway id (UUID) this route belongs to - see list_gateways") String gatewayId,
            @McpToolParam(description = "HTTP method, e.g. GET, POST") String httpMethod,
            @McpToolParam(description = "Route path, must start with '/'. Three shapes: an exact path (e.g. "
                    + "'/orders'); a path with one or more ':name' parameter segments matching any single segment "
                    + "there and capturing it (e.g. '/api/todos/:id' matches '/api/todos/42' - the invoked "
                    + "Function's handler receives it as event.pathParameters.id); or a single trailing '/*' "
                    + "wildcard owning an entire subtree (e.g. '/app/*', for a whole static site or its own "
                    + "internal sub-routing). ':name' and '*' cannot be combined in the same path.") String path,
            @McpToolParam(description = "Route priority when paths could overlap, defaults to 100", required = false) Integer priority
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(CurrentMcpUser.id());
        Flow created = flowService.createFlow(appUser, new FlowCreateRequest(
                flowKey, name, description, UUID.fromString(gatewayId), httpMethod, path, priority));
        return flowMapper.toResponse(created);
    }

    @McpTool(name = "update_flow", description = "Update a Flow's name, description, gateway, method, path or priority.")
    public FlowResponse updateFlow(
            @McpToolParam(description = "Flow id (UUID)") String flowId,
            @McpToolParam(description = "Display name") String name,
            @McpToolParam(description = "Description", required = false) String description,
            @McpToolParam(description = "Gateway id (UUID)") String gatewayId,
            @McpToolParam(description = "HTTP method, e.g. GET, POST") String httpMethod,
            @McpToolParam(description = "Route path, must start with '/'. Same three shapes as create_flow: an "
                    + "exact path, a ':name'-parameter path (captured into event.pathParameters), or a single "
                    + "trailing '/*' wildcard.") String path,
            @McpToolParam(description = "Route priority", required = false) Integer priority
    ) {
        Flow updated = flowService.updateFlow(CurrentMcpUser.id(), UUID.fromString(flowId), new FlowUpdateRequest(
                name, description, UUID.fromString(gatewayId), httpMethod, path, priority));
        return flowMapper.toResponse(updated);
    }

    @McpTool(name = "delete_flow", description = "Delete (soft-delete) a Flow and its versions.")
    public Map<String, String> deleteFlow(@McpToolParam(description = "Flow id (UUID)") String flowId) {
        flowService.deleteFlow(CurrentMcpUser.id(), UUID.fromString(flowId));
        return Map.of("message", "Flow deleted successfully");
    }
}
