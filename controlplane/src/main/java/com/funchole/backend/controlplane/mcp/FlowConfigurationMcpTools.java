package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.dto.FlowDatabaseAttachmentResponse;
import com.funchole.backend.controlplane.dto.FlowEnvironmentAttachmentResponse;
import com.funchole.backend.controlplane.service.FlowConfigurationService;
import java.util.List;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP tool surface for attaching shared resources to a Flow (as opposed to
 * one FunctionVersion) - an EnvironmentProfile's env vars/secrets, or a
 * Database - so multiple steps under the same Flow can share them. Mirrors
 * {@code FlowConfigurationController} exactly.
 */
@Service
public class FlowConfigurationMcpTools {

    private final FlowConfigurationService flowConfigurationService;

    public FlowConfigurationMcpTools(FlowConfigurationService flowConfigurationService) {
        this.flowConfigurationService = flowConfigurationService;
    }

    @McpTool(name = "list_flow_environments", description = "List the EnvironmentProfiles attached to a Flow, in priority order.")
    public List<FlowEnvironmentAttachmentResponse> listFlowEnvironments(@McpToolParam(description = "Flow id (UUID)") String flowId) {
        return flowConfigurationService.listEnvironmentAttachments(CurrentMcpUser.id(), UUID.fromString(flowId));
    }

    @McpTool(
            name = "attach_flow_environment",
            description = "Attach an EnvironmentProfile to a Flow. Lower priority numbers are applied first, so a "
                    + "higher-priority profile's variables override a lower one's on key collision."
    )
    public List<FlowEnvironmentAttachmentResponse> attachFlowEnvironment(
            @McpToolParam(description = "Flow id (UUID)") String flowId,
            @McpToolParam(description = "EnvironmentProfile id (UUID) - see list_environments") String environmentId,
            @McpToolParam(description = "Priority, defaults to 100", required = false) Integer priority
    ) {
        return flowConfigurationService.attachEnvironment(
                CurrentMcpUser.id(), UUID.fromString(flowId), UUID.fromString(environmentId), priority);
    }

    @McpTool(name = "detach_flow_environment", description = "Detach an EnvironmentProfile from a Flow.")
    public List<FlowEnvironmentAttachmentResponse> detachFlowEnvironment(
            @McpToolParam(description = "Flow id (UUID)") String flowId,
            @McpToolParam(description = "EnvironmentProfile id (UUID)") String environmentId
    ) {
        return flowConfigurationService.detachEnvironment(CurrentMcpUser.id(), UUID.fromString(flowId), UUID.fromString(environmentId));
    }

    @McpTool(name = "list_flow_databases", description = "List the Database resources attached to a Flow.")
    public List<FlowDatabaseAttachmentResponse> listFlowDatabases(@McpToolParam(description = "Flow id (UUID)") String flowId) {
        return flowConfigurationService.listDatabaseAttachments(CurrentMcpUser.id(), UUID.fromString(flowId));
    }

    @McpTool(name = "attach_flow_database", description = "Share an external Postgres resource across Flow steps through context.db(resourceName). Read get_funchole_guide('data') for pg.Pool usage and one-off Function migrations.")
    public List<FlowDatabaseAttachmentResponse> attachFlowDatabase(
            @McpToolParam(description = "Flow id (UUID)") String flowId,
            @McpToolParam(description = "Database id (UUID) - see list_databases") String databaseId
    ) {
        return flowConfigurationService.attachDatabase(CurrentMcpUser.id(), UUID.fromString(flowId), UUID.fromString(databaseId));
    }

    @McpTool(name = "detach_flow_database", description = "Detach a Database resource from a Flow.")
    public List<FlowDatabaseAttachmentResponse> detachFlowDatabase(
            @McpToolParam(description = "Flow id (UUID)") String flowId,
            @McpToolParam(description = "Database id (UUID)") String databaseId
    ) {
        return flowConfigurationService.detachDatabase(CurrentMcpUser.id(), UUID.fromString(flowId), UUID.fromString(databaseId));
    }
}
