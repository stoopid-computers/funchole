package com.funchole.backend.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import java.util.Map;

/**
 * Wire shape of the INVOKE payload received from the Dispatcher over IPC.
 * This is the complete handoff contract - the worker never re-queries the
 * FuncHole database for any of this data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record RuntimeInvokePayload(
        UUID invocationId,
        UUID flowId,
        UUID flowVersionId,
        UUID stepId,
        int attempt,
        String componentType,
        UUID componentId,
        UUID componentVersionId,
        String runtimeType,
        String input,
        Map<String, String> environment,
        List<DatabaseConnectionInfo> databases,
        UUID tenantId
) {
    public RuntimeInvokePayload {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        databases = databases == null ? List.of() : List.copyOf(databases);
    }
}
