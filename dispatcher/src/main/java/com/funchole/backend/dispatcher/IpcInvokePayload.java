package com.funchole.backend.dispatcher;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import java.util.Map;

/**
 * Wire shape of the INVOKE payload sent to a Runtime Worker over IPC. Mirrors
 * (but intentionally does not share code with) the Runtime Worker's own copy
 * of this shape in the {@code runtime} module - the two sides only share a
 * documented JSON contract, not a Java dependency, so each side of the
 * process boundary can evolve independently.
 */
record IpcInvokePayload(
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
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID tenantId
) {

    public IpcInvokePayload {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        databases = databases == null ? List.of() : List.copyOf(databases);
    }

    static IpcInvokePayload from(RuntimeExecutionRequest request) {
        return new IpcInvokePayload(
                request.invocationId(),
                request.flowId(),
                request.flowVersionId(),
                request.stepId(),
                request.attempt(),
                request.componentType(),
                request.componentId(),
                request.componentVersionId(),
                request.runtimeType(),
                request.input(),
                request.environment(),
                request.databases(),
                request.tenantId()
        );
    }
}
