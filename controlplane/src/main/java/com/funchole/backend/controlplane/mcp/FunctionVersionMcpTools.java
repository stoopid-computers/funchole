package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.dto.FunctionVersionBuildLogResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionConfigResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionCreateRequest;
import com.funchole.backend.controlplane.dto.FunctionVersionDatabaseAttachmentResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionFullSourceResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionSourceFileResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionSourceResponse;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.entity.FunctionVersionBuildLog;
import com.funchole.backend.controlplane.entity.FunctionVersionSource;
import com.funchole.backend.controlplane.entity.SourceBundle;
import com.funchole.backend.controlplane.entity.SourceFile;
import com.funchole.backend.controlplane.mapper.FunctionVersionMapper;
import com.funchole.backend.controlplane.service.FunctionVersionBuildLogService;
import com.funchole.backend.controlplane.service.FunctionVersionCloneService;
import com.funchole.backend.controlplane.service.FunctionVersionConfigService;
import com.funchole.backend.controlplane.service.FunctionVersionDatabaseService;
import com.funchole.backend.controlplane.service.FunctionVersionDeploymentService;
import com.funchole.backend.controlplane.service.FunctionVersionService;
import com.funchole.backend.controlplane.service.FunctionVersionSourceService;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * MCP tool surface for the FunctionVersion lifecycle - version history,
 * source submission, deploy, shared env/secret config, and Database
 * attachment. Each tool mirrors its REST equivalent exactly (same service
 * calls, same ownership checks); source submission bypasses the REST
 * layer's multipart adapter entirely and calls
 * {@link FunctionVersionSourceService} directly with a simple path/content
 * list, since there is no multipart concept in an MCP tool call.
 */
@Service
public class FunctionVersionMcpTools {

    private final FunctionVersionService functionVersionService;
    private final FunctionVersionCloneService functionVersionCloneService;
    private final FunctionVersionSourceService functionVersionSourceService;
    private final FunctionVersionDeploymentService functionVersionDeploymentService;
    private final FunctionVersionConfigService functionVersionConfigService;
    private final FunctionVersionDatabaseService functionVersionDatabaseService;
    private final FunctionVersionBuildLogService functionVersionBuildLogService;
    private final FunctionVersionMapper functionVersionMapper;

    public FunctionVersionMcpTools(
            FunctionVersionService functionVersionService,
            FunctionVersionCloneService functionVersionCloneService,
            FunctionVersionSourceService functionVersionSourceService,
            FunctionVersionDeploymentService functionVersionDeploymentService,
            FunctionVersionConfigService functionVersionConfigService,
            FunctionVersionDatabaseService functionVersionDatabaseService,
            FunctionVersionBuildLogService functionVersionBuildLogService,
            FunctionVersionMapper functionVersionMapper
    ) {
        this.functionVersionService = functionVersionService;
        this.functionVersionCloneService = functionVersionCloneService;
        this.functionVersionSourceService = functionVersionSourceService;
        this.functionVersionDeploymentService = functionVersionDeploymentService;
        this.functionVersionConfigService = functionVersionConfigService;
        this.functionVersionDatabaseService = functionVersionDatabaseService;
        this.functionVersionBuildLogService = functionVersionBuildLogService;
        this.functionVersionMapper = functionVersionMapper;
    }

    public record SourceFileInput(String path, String content) {
    }

    @McpTool(name = "list_function_versions", description = "List a Function's versions, most recent first.")
    public List<FunctionVersionResponse> listFunctionVersions(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "1-based page number, defaults to 1", required = false) Integer page,
            @McpToolParam(description = "Page size, defaults to 20", required = false) Integer size
    ) {
        Page<FunctionVersionResponse> result = functionVersionService
                .listVersions(CurrentMcpUser.id(), UUID.fromString(functionId), page != null ? page : 1, size != null ? size : 20)
                .map(functionVersionMapper::toResponse);
        return result.getContent();
    }

    @McpTool(name = "get_function_version", description = "Get one FunctionVersion by id, including its status (DRAFT/PUBLISHING/READY/FAILED).")
    public FunctionVersionResponse getFunctionVersion(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId
    ) {
        FunctionVersion version = functionVersionService.getVersionById(
                CurrentMcpUser.id(), UUID.fromString(functionId), UUID.fromString(versionId));
        return functionVersionMapper.toResponse(version);
    }

    @McpTool(
            name = "create_function_version",
            description = "Create a DRAFT, cloning the latest version's source/config/databases by default, even "
                    + "after FAILED builds. Read the cloned source before edits; submission replaces all files. "
                    + "Use cloneFromVersionId for another starting revision or startEmpty=true for a blank draft. "
                    + "Read get_funchole_guide('evolve') for fix-forward updates."
    )
    public FunctionVersionResponse createFunctionVersion(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "Runtime: NODE (runs your handler code) or STATIC (serves a pre-built "
                    + "static site's files directly, no code execution) - optional, defaults to the cloned "
                    + "version's runtime, or the parent Function's own runtime if there is nothing to clone",
                    required = false) String runtime,
            @McpToolParam(description = "Free-form metadata string - optional, never cloned from a prior version",
                    required = false) String metadata,
            @McpToolParam(description = "Clone this specific prior FunctionVersion id (UUID) instead of the "
                    + "Function's most recent version - optional", required = false) String cloneFromVersionId,
            @McpToolParam(description = "true creates a genuinely empty DRAFT with no source/config, opting out "
                    + "of the default auto-clone - optional, defaults to false", required = false) Boolean startEmpty
    ) {
        FunctionVersion version = functionVersionCloneService.createDraftVersion(
                CurrentMcpUser.id(), UUID.fromString(functionId), new FunctionVersionCreateRequest(
                        runtime, metadata,
                        cloneFromVersionId != null ? UUID.fromString(cloneFromVersionId) : null,
                        startEmpty));
        return functionVersionMapper.toResponse(version);
    }

    @McpTool(
            name = "submit_function_version_source",
            description = "Replace ALL source files on a DRAFT using relative paths and full text content; "
                    + "omitted files are removed. Before first submission, read get_funchole_guide('static' or "
                    + "'node') and get_function_example for the matching contract. STATIC uses package.json and "
                    + "a build script; NODE RESPONSE uses {status, body, headers?}, with JSON-serialized body. "
                    + "Builds and handlers have unsandboxed host access: trusted code only, attached resources "
                    + "for secrets, no credential harvesting, unexpected outbound calls or destructive host operations."
    )
    public FunctionVersionSourceResponse submitFunctionVersionSource(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId,
            @McpToolParam(description = "Source files, each with a relative path and its full text content") List<SourceFileInput> files,
            @McpToolParam(description = "NODE module path, e.g. 'index.mjs'; STATIC uses 'package.json'") String entrypoint,
            @McpToolParam(description = "Exported NODE function name, defaults to 'handler'; omit for STATIC", required = false) String handler
    ) {
        UUID versionUuid = UUID.fromString(versionId);
        FunctionVersion functionVersion = functionVersionService.getVersionById(
                CurrentMcpUser.id(), UUID.fromString(functionId), versionUuid);

        List<SourceFile> sourceFiles = files.stream().map(f -> new SourceFile(f.path(), f.content())).toList();
        SourceBundle bundle = new SourceBundle(functionVersion.getRuntime(), null, entrypoint, handler, sourceFiles);
        FunctionVersionSource source = functionVersionSourceService.submitSource(versionUuid, bundle);

        return new FunctionVersionSourceResponse(
                source.getFunctionVersionId(),
                source.getRuntimeType(),
                source.getRuntimeVersion(),
                source.getEntrypoint(),
                source.getHandler(),
                sourceFiles.stream().map(SourceFile::relativePath).toList()
        );
    }

    @McpTool(name = "get_function_version_source", description = "Read a FunctionVersion's submitted source, including each file's full content.")
    public FunctionVersionFullSourceResponse getFunctionVersionSource(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId
    ) {
        UUID versionUuid = UUID.fromString(versionId);
        functionVersionService.getVersionById(CurrentMcpUser.id(), UUID.fromString(functionId), versionUuid);
        SourceBundle bundle = functionVersionSourceService.findSource(versionUuid)
                .orElseThrow(() -> new ResourceNotFoundException("No source submitted for function version: " + versionId));

        List<FunctionVersionSourceFileResponse> fileResponses = bundle.files().stream()
                .map(file -> new FunctionVersionSourceFileResponse(file.relativePath(), file.content()))
                .toList();
        return new FunctionVersionFullSourceResponse(bundle.entrypoint(), bundle.handler(), fileResponses);
    }

    @McpTool(
            name = "deploy_function_version",
            description = "Build a DRAFT with submitted source. Returns PUBLISHING immediately; poll "
                    + "get_function_version to READY/FAILED. On failure read get_function_version_build_logs "
                    + "and get_funchole_guide('troubleshooting'); FAILED needs a new cloned DRAFT, not redeploy in place."
    )
    public FunctionVersionResponse deployFunctionVersion(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId
    ) {
        UUID versionUuid = UUID.fromString(versionId);
        functionVersionService.getVersionById(CurrentMcpUser.id(), UUID.fromString(functionId), versionUuid);
        FunctionVersion deployed = functionVersionDeploymentService.deploy(versionUuid);
        return functionVersionMapper.toResponse(deployed);
    }

    @McpTool(
            name = "get_function_version_build_logs",
            description = "Read a FunctionVersion's persisted build-stage diagnostics: every dependency-install/"
                    + "build stage a deploy attempt ran, in order, each with its exact command, exit code, and full "
                    + "stdout/stderr - whether that stage succeeded or failed. Durable and queryable at any time "
                    + "after deploy_function_version returns, not just in the same session as the failure. Returns "
                    + "an empty list for a version that was never deployed, or whose runtime never needed to run a "
                    + "build stage (e.g. a dependency-free NODE version with no package.json)."
    )
    public List<FunctionVersionBuildLogResponse> getFunctionVersionBuildLogs(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId
    ) {
        UUID versionUuid = UUID.fromString(versionId);
        functionVersionService.getVersionById(CurrentMcpUser.id(), UUID.fromString(functionId), versionUuid);
        return functionVersionBuildLogService.listLogs(versionUuid).stream()
                .map(FunctionVersionMcpTools::toBuildLogResponse)
                .toList();
    }

    private static FunctionVersionBuildLogResponse toBuildLogResponse(FunctionVersionBuildLog log) {
        return new FunctionVersionBuildLogResponse(
                log.getStage(), log.getCommand(), log.getExitCode(), log.isSucceeded(), log.isTimedOut(),
                log.getStdout(), log.getStderr(), log.getCreatedAt());
    }

    @McpTool(name = "get_function_version_config", description = "Get a FunctionVersion's environment variables and secret keys (secret values are never returned, only their reference).")
    public FunctionVersionConfigResponse getFunctionVersionConfig(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId
    ) {
        return functionVersionConfigService.getConfig(CurrentMcpUser.id(), UUID.fromString(functionId), UUID.fromString(versionId));
    }

    @McpTool(name = "set_function_version_env_var", description = "Create or update one non-secret environment variable on a FunctionVersion.")
    public FunctionVersionConfigResponse setFunctionVersionEnvVar(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId,
            @McpToolParam(description = "Variable name") String key,
            @McpToolParam(description = "Variable value") String value
    ) {
        return functionVersionConfigService.upsertEnvVar(
                CurrentMcpUser.id(), UUID.fromString(functionId), UUID.fromString(versionId), key, value);
    }

    @McpTool(name = "set_function_version_secret", description = "Create or update one secret on a FunctionVersion. The value is stored in OpenBao, never returned again afterward.")
    public FunctionVersionConfigResponse setFunctionVersionSecret(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId,
            @McpToolParam(description = "Secret name") String key,
            @McpToolParam(description = "Secret value") String value
    ) {
        return functionVersionConfigService.upsertSecret(
                CurrentMcpUser.id(), UUID.fromString(functionId), UUID.fromString(versionId), key, value);
    }

    @McpTool(name = "list_function_version_databases", description = "List the Database resources attached to a FunctionVersion.")
    public List<FunctionVersionDatabaseAttachmentResponse> listFunctionVersionDatabases(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId
    ) {
        return functionVersionDatabaseService.listAttachments(CurrentMcpUser.id(), UUID.fromString(functionId), UUID.fromString(versionId));
    }

    @McpTool(
            name = "attach_function_version_database",
            description = "Attach an existing external Postgres resource. handler(input, context) reaches it "
                    + "via context.db(resourceName), a pg.Pool with query(sql, params). Read "
                    + "get_funchole_guide('data') and get_function_example('NODE_DATABASE') before data/migration code."
    )
    public List<FunctionVersionDatabaseAttachmentResponse> attachFunctionVersionDatabase(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId,
            @McpToolParam(description = "Database id (UUID) - see list_databases") String databaseId
    ) {
        return functionVersionDatabaseService.attachDatabase(
                CurrentMcpUser.id(), UUID.fromString(functionId), UUID.fromString(versionId), UUID.fromString(databaseId));
    }

    @McpTool(name = "detach_function_version_database", description = "Detach a Database resource from a FunctionVersion.")
    public List<FunctionVersionDatabaseAttachmentResponse> detachFunctionVersionDatabase(
            @McpToolParam(description = "Function id (UUID)") String functionId,
            @McpToolParam(description = "FunctionVersion id (UUID)") String versionId,
            @McpToolParam(description = "Database id (UUID)") String databaseId
    ) {
        return functionVersionDatabaseService.detachDatabase(
                CurrentMcpUser.id(), UUID.fromString(functionId), UUID.fromString(versionId), UUID.fromString(databaseId));
    }
}
