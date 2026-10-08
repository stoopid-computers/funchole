package com.funchole.backend.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Java Runtime Worker -> Node Executor handoff. Everything the artifact
 * needs is already here; the Node process never queries the FuncHole
 * database.
 */
public record NodeExecutionRequest(
        UUID executionId,
        UUID componentId,
        UUID componentVersionId,
        Path artifactPath,
        String handler,
        String input,
        Map<String, String> environment,
        List<DatabaseConnectionInfo> databases,
        UUID tenantId
) {
    public NodeExecutionRequest {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        databases = databases == null ? List.of() : List.copyOf(databases);
    }

    /** Before the tenant id existed: kept so every earlier caller compiles unchanged. */
    public NodeExecutionRequest(
            UUID executionId,
            UUID componentId,
            UUID componentVersionId,
            Path artifactPath,
            String handler,
            String input,
            Map<String, String> environment,
            List<DatabaseConnectionInfo> databases
    ) {
        this(executionId, componentId, componentVersionId, artifactPath, handler, input, environment, databases, null);
    }

    public NodeExecutionRequest(
            UUID executionId,
            UUID componentId,
            UUID componentVersionId,
            Path artifactPath,
            String handler,
            String input
    ) {
        this(executionId, componentId, componentVersionId, artifactPath, handler, input, Map.of(), List.of(), null);
    }
}
