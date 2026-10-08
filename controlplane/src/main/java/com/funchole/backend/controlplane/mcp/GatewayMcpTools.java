package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.dto.GatewayCreateRequest;
import com.funchole.backend.controlplane.dto.GatewayResponse;
import com.funchole.backend.controlplane.dto.GatewayUpdateRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.mapper.GatewayMapper;
import com.funchole.backend.controlplane.service.GatewayService;
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
 * MCP tool surface for Gateways - mirrors {@code GatewayController} exactly.
 * A Gateway binds to one verified AppDomain and hosts the Flows (HTTP
 * routes) registered under it.
 */
@Service
public class GatewayMcpTools {

    private final GatewayService gatewayService;
    private final ProfileService profileService;
    private final GatewayMapper gatewayMapper;

    public GatewayMcpTools(GatewayService gatewayService, ProfileService profileService, GatewayMapper gatewayMapper) {
        this.gatewayService = gatewayService;
        this.profileService = profileService;
        this.gatewayMapper = gatewayMapper;
    }

    @McpTool(name = "list_gateways", description = "List the current user's Gateways.")
    public List<GatewayResponse> listGateways(
            @McpToolParam(description = "1-based page number, defaults to 1", required = false) Integer page,
            @McpToolParam(description = "Page size, defaults to 20", required = false) Integer size
    ) {
        Page<GatewayResponse> result = gatewayService
                .listGateways(CurrentMcpUser.id(), page != null ? page : 1, size != null ? size : 20)
                .map(gatewayMapper::toResponse);
        return result.getContent();
    }

    @McpTool(name = "get_gateway", description = "Get one Gateway by id.")
    public GatewayResponse getGateway(@McpToolParam(description = "Gateway id (UUID)") String gatewayId) {
        return gatewayMapper.toResponse(gatewayService.getGatewayById(CurrentMcpUser.id(), UUID.fromString(gatewayId)));
    }

    @McpTool(
            name = "create_gateway",
            description = "Create a new Gateway bound to a verified AppDomain - see create_domain first if you "
                    + "don't have one yet. Create Flows under it next with create_flow. appDomainId is required "
                    + "for the admin account; other users (cloud product only) get a randomly chosen verified "
                    + "domain automatically and can omit it."
    )
    public GatewayResponse createGateway(
            @McpToolParam(description = "Display name") String name,
            @McpToolParam(description = "Description", required = false) String description,
            @McpToolParam(description = "AppDomain id (UUID) - see list_domains. Admin only; other users omit this.", required = false)
                    String appDomainId,
            @McpToolParam(description = "ACTIVE or INACTIVE") String status
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(CurrentMcpUser.id());
        UUID appDomainUuid = appDomainId == null ? null : UUID.fromString(appDomainId);
        Gateway created = gatewayService.createGateway(appUser, new GatewayCreateRequest(
                name, description, appDomainUuid, GatewayStatus.valueOf(status)));
        return gatewayMapper.toResponse(created);
    }

    @McpTool(name = "update_gateway", description = "Update a Gateway's name, description, domain or status.")
    public GatewayResponse updateGateway(
            @McpToolParam(description = "Gateway id (UUID)") String gatewayId,
            @McpToolParam(description = "Display name") String name,
            @McpToolParam(description = "Description", required = false) String description,
            @McpToolParam(description = "AppDomain id (UUID). Admin only; other users omit this.", required = false) String appDomainId,
            @McpToolParam(description = "ACTIVE or INACTIVE") String status
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(CurrentMcpUser.id());
        Gateway updated = gatewayService.updateGateway(appUser, UUID.fromString(gatewayId), new GatewayUpdateRequest(
                name, description, appDomainId == null ? null : UUID.fromString(appDomainId), GatewayStatus.valueOf(status)));
        return gatewayMapper.toResponse(updated);
    }

    @McpTool(name = "delete_gateway", description = "Delete a Gateway.")
    public Map<String, String> deleteGateway(@McpToolParam(description = "Gateway id (UUID)") String gatewayId) {
        gatewayService.deleteGateway(CurrentMcpUser.id(), UUID.fromString(gatewayId));
        return Map.of("message", "Gateway deleted successfully");
    }
}
