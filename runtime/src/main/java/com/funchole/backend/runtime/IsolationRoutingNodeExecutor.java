package com.funchole.backend.runtime;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Sends each execution to the shared Node process or to the tenant's sandbox, per
 * {@link IsolationMode#resolve}. This is what lets the sandbox be switched on for a few tenants
 * first and rolled back by changing a setting.
 */
final class IsolationRoutingNodeExecutor implements NodeExecutor, AutoCloseable {

    private final IsolationMode defaultMode;
    private final Set<String> sandboxTenants;
    private final NodeExecutor legacy;
    private final NodeExecutor sandbox;

    IsolationRoutingNodeExecutor(IsolationMode defaultMode, Set<String> sandboxTenants, NodeExecutor legacy, NodeExecutor sandbox) {
        this.defaultMode = defaultMode;
        this.sandboxTenants = sandboxTenants;
        this.legacy = legacy;
        this.sandbox = sandbox;
    }

    @Override
    public CompletionStage<NodeExecutionResult> execute(NodeExecutionRequest request, Consumer<NodeLogMessage> onLog) {
        String tenant = request.tenantId() == null ? null : request.tenantId().toString();
        IsolationMode mode = IsolationMode.resolve(defaultMode, sandboxTenants, tenant);
        NodeExecutor target = mode == IsolationMode.SANDBOX ? sandbox : legacy;
        if (target == null) {
            return CompletableFuture.completedFuture(NodeExecutionResult.failure(
                    request.executionId(), "ISOLATION_UNAVAILABLE", "No executor is configured for isolation mode " + mode));
        }
        return target.execute(request, onLog);
    }

    @Override
    public void close() {
        for (NodeExecutor executor : new NodeExecutor[] {legacy, sandbox}) {
            if (executor instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // Best-effort shutdown.
                }
            }
        }
    }
}
