package com.funchole.backend.controlplane.service.workflow;

import com.funchole.backend.controlplane.dto.FunctionCreateRequest;
import com.funchole.backend.controlplane.dto.FunctionVersionCreateRequest;
import com.funchole.backend.controlplane.dto.FunctionVersionResponse;
import com.funchole.backend.controlplane.entity.SourceBundle;
import com.funchole.backend.controlplane.entity.SourceFile;
import com.funchole.backend.controlplane.mapper.FunctionVersionMapper;
import com.funchole.backend.controlplane.service.DatabaseService;
import com.funchole.backend.controlplane.service.FunctionService;
import com.funchole.backend.controlplane.service.FunctionVersionCloneService;
import com.funchole.backend.controlplane.service.FunctionVersionConfigService;
import com.funchole.backend.controlplane.service.FunctionVersionDatabaseService;
import com.funchole.backend.controlplane.service.FunctionVersionDeploymentService;
import com.funchole.backend.controlplane.service.FunctionVersionService;
import com.funchole.backend.controlplane.service.FunctionVersionSourceService;
import com.funchole.backend.controlplane.service.ProfileService;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Complete source replacement followed by an asynchronous build. */
@Service
public class BuildFunctionUseCase {
    public record Command(UUID functionId, UUID baseVersionId, String key, String name, String runtime,
                          String entrypoint, String handler, List<SourceFile> files,
                          Map<String, String> env, Map<String, String> secrets,
                          List<UUID> addDatabases, List<UUID> removeDatabases) { }
    public record Result(UUID functionId, UUID versionId, FunctionVersionResponse version, boolean partialFailure) { }

    private final ProfileService profiles;
    private final FunctionService functions;
    private final FunctionVersionService versions;
    private final FunctionVersionCloneService clones;
    private final FunctionVersionSourceService sources;
    private final FunctionVersionConfigService configs;
    private final FunctionVersionDatabaseService attachments;
    private final FunctionVersionDeploymentService deployment;
    private final DatabaseService databases;
    private final FunctionVersionMapper mapper;

    public BuildFunctionUseCase(ProfileService profiles, FunctionService functions, FunctionVersionService versions,
            FunctionVersionCloneService clones, FunctionVersionSourceService sources,
            FunctionVersionConfigService configs, FunctionVersionDatabaseService attachments,
            FunctionVersionDeploymentService deployment, DatabaseService databases, FunctionVersionMapper mapper) {
        this.profiles = profiles;
        this.functions = functions;
        this.versions = versions;
        this.clones = clones;
        this.sources = sources;
        this.configs = configs;
        this.attachments = attachments;
        this.deployment = deployment;
        this.databases = databases;
        this.mapper = mapper;
    }

    public Result execute(UUID userId, Command c) throws Exception {
        require(userId != null && c != null && c.files() != null && !c.files().isEmpty() && c.files().size() <= 10000);
        Set<String> paths = new HashSet<>();
        long bytes = 0;
        for (SourceFile file : c.files()) {
            require(file != null && file.relativePath() != null && file.content() != null);
            String path = file.relativePath();
            require(!path.isBlank() && !path.startsWith("/") && !path.contains("\\") && !path.matches("^[A-Za-z]:.*") && path.indexOf('\0') < 0);
            for (String segment : path.split("/", -1)) require(!segment.isEmpty() && !segment.equals(".") && !segment.equals(".."));
            require(paths.add(path));
            bytes += file.content().getBytes(StandardCharsets.UTF_8).length + path.getBytes(StandardCharsets.UTF_8).length;
            require(bytes <= 7L * 1024 * 1024);
        }
        require(c.entrypoint() != null && paths.contains(c.entrypoint()));
        validateConfig(c.env());
        validateConfig(c.secrets());
        require(Collections.disjoint(keys(c.env()), keys(c.secrets())));
        List<UUID> add = c.addDatabases() == null ? List.of() : c.addDatabases();
        List<UUID> remove = c.removeDatabases() == null ? List.of() : c.removeDatabases();
        require(new HashSet<>(add).size() == add.size() && new HashSet<>(remove).size() == remove.size()
                && Collections.disjoint(add, remove));
        String runtime = c.runtime();
        if (c.functionId() == null) {
            require(c.baseVersionId() == null && runtime != null && remove.isEmpty());
            identity(c.key(), c.name());
        } else {
            require(c.key() == null && c.name() == null && c.baseVersionId() != null);
            functions.getFunctionById(userId, c.functionId());
            var base = versions.getVersionById(userId, c.functionId(), c.baseVersionId());
            if (runtime == null) runtime = base.getRuntime();
            var inherited = configs.getConfig(userId, c.functionId(), c.baseVersionId());
            require(inherited.secrets().stream().noneMatch(s -> keys(c.env()).contains(s.key())));
            require(inherited.envVars().stream().noneMatch(e -> keys(c.secrets()).contains(e.key())));
        }
        require("NODE".equals(runtime) || "STATIC".equals(runtime));
        if ("STATIC".equals(runtime)) require("package.json".equals(c.entrypoint()) && c.handler() == null);
        if (c.handler() != null) require(c.handler().matches("[A-Za-z_$][A-Za-z0-9_$]*"));
        for (UUID id : add) databases.getDatabaseById(userId, id);
        for (UUID id : remove) databases.getDatabaseById(userId, id);

        UUID functionId = c.functionId();
        UUID versionId = null;
        try {
            if (functionId == null) functionId = functions.createFunction(profiles.loadUserById(userId),
                    new FunctionCreateRequest(c.key(), c.name(), null, runtime)).getId();
            var draft = clones.createDraftVersion(userId, functionId,
                    new FunctionVersionCreateRequest(runtime, null, c.baseVersionId(), c.baseVersionId() == null));
            versionId = draft.getId();
            sources.submitSource(versionId, new SourceBundle(runtime, null, c.entrypoint(), c.handler(), c.files()));
            if (c.env() != null) for (var e : c.env().entrySet()) configs.upsertEnvVar(userId, functionId, versionId, e.getKey(), e.getValue());
            if (c.secrets() != null) for (var e : c.secrets().entrySet()) configs.upsertSecret(userId, functionId, versionId, e.getKey(), e.getValue());
            for (UUID id : remove) attachments.detachDatabase(userId, functionId, versionId, id);
            for (UUID id : add) attachments.attachDatabase(userId, functionId, versionId, id);
            return new Result(functionId, versionId, mapper.toResponse(deployment.deploy(versionId)), false);
        } catch (Exception failure) {
            if (functionId == null) throw failure;
            return new Result(functionId, versionId, null, true);
        }
    }

    private static void identity(String key, String name) {
        require(key != null && key.matches("[A-Za-z0-9_.-]{1,150}") && name != null && !name.isBlank() && name.length() <= 255);
    }
    private static void validateConfig(Map<String, String> values) {
        if (values != null) values.forEach((key, value) -> require(key != null
                && key.matches("[A-Za-z_][A-Za-z0-9_]{0,254}") && value != null));
    }
    private static Set<String> keys(Map<String, String> values) { return values == null ? Set.of() : values.keySet(); }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid build request"); }
}
