package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.dto.FunctionCreateRequest;
import com.funchole.backend.controlplane.dto.FunctionResponse;
import com.funchole.backend.controlplane.dto.FunctionUpdateRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.mapper.FunctionMapper;
import com.funchole.backend.controlplane.service.FunctionService;
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
 * MCP tool surface for Functions - mirrors {@code FunctionController}
 * exactly (same service calls, same per-AppUser scoping), just reached
 * through an MCP tool call instead of a REST request.
 */
@Service
public class FunctionMcpTools {

    private final FunctionService functionService;
    private final ProfileService profileService;
    private final FunctionMapper functionMapper;

    public FunctionMcpTools(FunctionService functionService, ProfileService profileService, FunctionMapper functionMapper) {
        this.functionService = functionService;
        this.profileService = profileService;
        this.functionMapper = functionMapper;
    }

    @McpTool(name = "list_functions", description = "List the current user's Functions, most recently created first.")
    public List<FunctionResponse> listFunctions(
            @McpToolParam(description = "1-based page number, defaults to 1", required = false) Integer page,
            @McpToolParam(description = "Page size, defaults to 20", required = false) Integer size
    ) {
        Page<FunctionResponse> result = functionService
                .listFunctions(CurrentMcpUser.id(), page != null ? page : 1, size != null ? size : 20)
                .map(functionMapper::toResponse);
        return result.getContent();
    }

    @McpTool(name = "get_function", description = "Get one Function by id.")
    public FunctionResponse getFunction(@McpToolParam(description = "Function id (UUID)") String functionId) {
        Function function = functionService.getFunctionById(CurrentMcpUser.id(), UUID.fromString(functionId));
        return functionMapper.toResponse(function);
    }

    @McpTool(
            name = "create_function",
            description = "Create a new Function - the container a FunctionVersion's source/build/deploy/invoke "
                    + "lifecycle happens under. Create a FunctionVersion under it next with create_function_version."
    )
    public FunctionResponse createFunction(
            @McpToolParam(description = "Unique key: letters, numbers, '_', '.' and '-' only, e.g. fn_hello_world") String functionKey,
            @McpToolParam(description = "Display name") String name,
            @McpToolParam(description = "Description", required = false) String description,
            @McpToolParam(description = "NODE for API/business logic, STATIC for browser files. Defaults to NODE. "
                    + "Before choosing, read get_funchole_guide('start'); for sites/shared assets read 'static'.", required = false) String runtime
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(CurrentMcpUser.id());
        Function created = functionService.createFunction(appUser, new FunctionCreateRequest(functionKey, name, description, runtime));
        return functionMapper.toResponse(created);
    }

    @McpTool(name = "update_function", description = "Update a Function's name, description or runtime.")
    public FunctionResponse updateFunction(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "Display name") String name,
            @McpToolParam(description = "Description", required = false) String description,
            @McpToolParam(description = "Runtime: NODE (runs your handler code - use for a backend/API function) "
                    + "or STATIC (serves a pre-built static site's files directly, no code execution - use for a "
                    + "frontend/UI)", required = false) String runtime
    ) {
        Function updated = functionService.updateFunction(
                CurrentMcpUser.id(), UUID.fromString(functionId), new FunctionUpdateRequest(name, description, runtime));
        return functionMapper.toResponse(updated);
    }

    @McpTool(name = "delete_function", description = "Delete (soft-delete) a Function and everything under it.")
    public Map<String, String> deleteFunction(@McpToolParam(description = "Function id (UUID)") String functionId) {
        functionService.deleteFunction(CurrentMcpUser.id(), UUID.fromString(functionId));
        return Map.of("message", "Function deleted successfully");
    }
}
