package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.dto.DatabaseCreateRequest;
import com.funchole.backend.controlplane.dto.DatabaseResponse;
import com.funchole.backend.controlplane.dto.DatabaseUpdateRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Database;
import com.funchole.backend.controlplane.mapper.DatabaseMapper;
import com.funchole.backend.controlplane.service.DatabaseService;
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
 * MCP tool surface for the managed Database resource - mirrors
 * {@code DatabaseController} exactly. Currently Postgres, external
 * connections only (see EPIC-18/F352); the password is written straight to
 * OpenBao and never returned by any of these tools, same as the REST API.
 */
@Service
public class DatabaseMcpTools {

    private final DatabaseService databaseService;
    private final ProfileService profileService;
    private final DatabaseMapper databaseMapper;

    public DatabaseMcpTools(DatabaseService databaseService, ProfileService profileService, DatabaseMapper databaseMapper) {
        this.databaseService = databaseService;
        this.profileService = profileService;
        this.databaseMapper = databaseMapper;
    }

    @McpTool(name = "list_databases", description = "List the current user's managed Database resources.")
    public List<DatabaseResponse> listDatabases(
            @McpToolParam(description = "1-based page number, defaults to 1", required = false) Integer page,
            @McpToolParam(description = "Page size, defaults to 20", required = false) Integer size
    ) {
        Page<DatabaseResponse> result = databaseService
                .listDatabases(CurrentMcpUser.id(), page != null ? page : 1, size != null ? size : 20)
                .map(databaseMapper::toResponse);
        return result.getContent();
    }

    @McpTool(name = "get_database", description = "Get one Database resource by id.")
    public DatabaseResponse getDatabase(@McpToolParam(description = "Database id (UUID)") String databaseId) {
        return databaseMapper.toResponse(databaseService.getDatabaseById(CurrentMcpUser.id(), UUID.fromString(databaseId)));
    }

    @McpTool(
            name = "reveal_database_password",
            description = "Reveal a Database resource's password - a separate explicit call, never included in "
                    + "list_databases/get_database, so it's only returned when specifically asked for."
    )
    public Map<String, String> revealDatabasePassword(@McpToolParam(description = "Database id (UUID)") String databaseId) {
        String password = databaseService.revealPassword(CurrentMcpUser.id(), UUID.fromString(databaseId));
        return Map.of("password", password);
    }

    @McpTool(
            name = "create_database",
            description = "Register an existing external Postgres connection; this does not provision Postgres. "
                    + "The password is stored securely and excluded from routine responses. Attach it to a "
                    + "Function Version or Flow; read get_funchole_guide('data') before database/migration code."
    )
    public DatabaseResponse createDatabase(
            @McpToolParam(description = "Unique name for this resource, e.g. 'primary'") String name,
            @McpToolParam(description = "Host") String host,
            @McpToolParam(description = "Port, e.g. 5432") Integer port,
            @McpToolParam(description = "Database name on the server") String databaseName,
            @McpToolParam(description = "Username") String username,
            @McpToolParam(description = "Password") String password,
            @McpToolParam(description = "Require SSL, defaults to true", required = false) Boolean sslEnabled
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(CurrentMcpUser.id());
        Database created = databaseService.createDatabase(appUser, new DatabaseCreateRequest(
                name, "POSTGRES", host, port, databaseName, username, password, sslEnabled));
        return databaseMapper.toResponse(created);
    }

    @McpTool(name = "update_database", description = "Update a Database resource's connection details. Omit password to keep the current one.")
    public DatabaseResponse updateDatabase(
            @McpToolParam(description = "Database id (UUID)") String databaseId,
            @McpToolParam(description = "Unique name for this resource") String name,
            @McpToolParam(description = "Host") String host,
            @McpToolParam(description = "Port, e.g. 5432") Integer port,
            @McpToolParam(description = "Database name on the server") String databaseName,
            @McpToolParam(description = "Username") String username,
            @McpToolParam(description = "New password - omit to keep the current one", required = false) String password,
            @McpToolParam(description = "Require SSL", required = false) Boolean sslEnabled
    ) {
        Database updated = databaseService.updateDatabase(CurrentMcpUser.id(), UUID.fromString(databaseId), new DatabaseUpdateRequest(
                name, host, port, databaseName, username, password, sslEnabled));
        return databaseMapper.toResponse(updated);
    }

    @McpTool(name = "delete_database", description = "Delete (soft-delete) a Database resource.")
    public Map<String, String> deleteDatabase(@McpToolParam(description = "Database id (UUID)") String databaseId) {
        databaseService.deleteDatabase(CurrentMcpUser.id(), UUID.fromString(databaseId));
        return Map.of("message", "Database deleted successfully");
    }
}
