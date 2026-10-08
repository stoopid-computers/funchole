package com.funchole.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class IsolationRoutingNodeExecutorTest {

    private static NodeExecutor tagging(String tag) {
        return (request, onLog) -> CompletableFuture.completedFuture(NodeExecutionResult.success(request.executionId(), tag));
    }

    private static NodeExecutionRequest request(UUID tenant) {
        return new NodeExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Path.of("x.mjs"), "handler", "{}", Map.of(), List.of(), tenant);
    }

    private static String route(IsolationRoutingNodeExecutor executor, UUID tenant) throws Exception {
        return executor.execute(request(tenant), l -> { }).toCompletableFuture().get().output();
    }

    @Test
    void defaultsGoToTheDefaultModeAndAllowListedTenantsGoToTheSandbox() throws Exception {
        UUID canary = UUID.randomUUID();
        IsolationRoutingNodeExecutor executor = new IsolationRoutingNodeExecutor(
                IsolationMode.LEGACY, Set.of(canary.toString()), tagging("legacy"), tagging("sandbox"));

        assertThat(route(executor, UUID.randomUUID())).isEqualTo("legacy");
        assertThat(route(executor, null)).isEqualTo("legacy");
        assertThat(route(executor, canary)).isEqualTo("sandbox");
    }

    @Test
    void sandboxAsDefaultSendsEveryoneToTheSandbox() throws Exception {
        IsolationRoutingNodeExecutor executor = new IsolationRoutingNodeExecutor(IsolationMode.SANDBOX, Set.of(), null, tagging("sandbox"));
        assertThat(route(executor, UUID.randomUUID())).isEqualTo("sandbox");
    }

    @Test
    void anUnconfiguredTargetFailsLoudlyInsteadOfFallingBackToTheOtherMode() throws Exception {
        IsolationRoutingNodeExecutor executor = new IsolationRoutingNodeExecutor(IsolationMode.SANDBOX, Set.of(), tagging("legacy"), null);
        NodeExecutionResult result = executor.execute(request(UUID.randomUUID()), l -> { }).toCompletableFuture().get();
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("ISOLATION_UNAVAILABLE");
    }
}
