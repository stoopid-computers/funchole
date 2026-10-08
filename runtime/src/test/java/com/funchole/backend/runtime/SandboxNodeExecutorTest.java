package com.funchole.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Runs the real Node executor, one local process per "sandbox", so the pool logic (separation,
 * reuse, timeout, eviction, recycling, replacement) is tested without Docker. How a sandbox is
 * locked down is covered by the attack suite against real containers.
 */
class SandboxNodeExecutorTest {

    private static final Path EXECUTOR = Path.of("node", "executor.mjs").toAbsolutePath();
    private static final Path TRAP = Path.of("attack-suite", "fixtures", "tenant-trap.mjs").toAbsolutePath();
    private static final Path ECHO = Path.of("golden", "fixtures", "echo.mjs").toAbsolutePath();
    private static final Path SLOW = Path.of("golden", "fixtures", "slow.mjs").toAbsolutePath();
    private static final Path SPIN = Path.of("attack-suite", "fixtures", "spin.mjs").toAbsolutePath();

    private final LocalLauncher launcher = new LocalLauncher();
    private SandboxNodeExecutor executor;

    private SandboxNodeExecutor pool(int max, Duration idle, int recycle, Duration timeout) {
        executor = new SandboxNodeExecutor(launcher, new SandboxConfig(max, idle, recycle, timeout));
        return executor;
    }

    @AfterEach
    void tearDown() {
        if (executor != null) executor.close();
    }

    static final class LocalLauncher implements SandboxLauncher {
        final AtomicInteger launches = new AtomicInteger();
        final List<String> destroyed = new CopyOnWriteArrayList<>();
        final List<Process> processes = new CopyOnWriteArrayList<>();
        volatile boolean failToLaunch;

        @Override
        public Process launch(String name) throws IOException {
            if (failToLaunch) throw new IOException("no docker here");
            launches.incrementAndGet();
            Process process = new ProcessBuilder("node", EXECUTOR.toString()).start();
            processes.add(process);
            return process;
        }

        @Override
        public void destroy(String name) {
            destroyed.add(name);
        }
    }

    private NodeExecutionResult run(UUID tenant, Path artifact, String input, Map<String, String> env) throws Exception {
        NodeExecutionRequest request = new NodeExecutionRequest(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), artifact, "handler", input, env, List.of(), tenant);
        return executor.execute(request, log -> { }).toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    @Test
    void tenantsNeverShareAProcessSoOneCannotCaptureAnothersSecrets() throws Exception {
        pool(8, Duration.ofMinutes(5), 1000, Duration.ofSeconds(30));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        run(a, TRAP, "{\"action\":\"plant\"}", Map.of());
        run(b, ECHO, "{}", Map.of("B_SECRET", "tenant-b-secret-value"));
        NodeExecutionResult collected = run(a, TRAP, "{\"action\":\"collect\",\"victimSecret\":\"tenant-b-secret-value\"}", Map.of());

        assertThat(collected.success()).isTrue();
        assertThat(collected.output()).contains("\"leaked\":false");
        assertThat(launcher.launches.get()).isEqualTo(2);
    }

    @Test
    void aTenantReusesItsWarmSandbox() throws Exception {
        pool(8, Duration.ofMinutes(5), 1000, Duration.ofSeconds(30));
        UUID tenant = UUID.randomUUID();
        run(tenant, ECHO, "{\"n\":1}", Map.of());
        assertThat(run(tenant, ECHO, "{\"n\":2}", Map.of()).output()).isEqualTo("{\"ok\":true,\"input\":{\"n\":2}}");

        assertThat(launcher.launches.get()).isEqualTo(1);
        assertThat(executor.activeSandboxes()).isEqualTo(1);
    }

    @Test
    void workWithNoKnownTenantSharesOneSandbox() throws Exception {
        pool(8, Duration.ofMinutes(5), 1000, Duration.ofSeconds(30));
        run(null, ECHO, "{}", Map.of());
        run(null, ECHO, "{}", Map.of());
        assertThat(launcher.launches.get()).isEqualTo(1);
    }

    @Test
    void aFunctionThatRunsTooLongIsStoppedWithAClearErrorAndTheNextCallGetsAFreshSandbox() throws Exception {
        pool(8, Duration.ofMinutes(5), 1000, Duration.ofSeconds(1));
        UUID tenant = UUID.randomUUID();

        NodeExecutionResult timedOut = run(tenant, SPIN, "{}", Map.of());
        assertThat(timedOut.success()).isFalse();
        assertThat(timedOut.errorCode()).isEqualTo("EXECUTION_TIMEOUT");
        assertThat(executor.activeSandboxes()).isZero();
        assertThat(launcher.destroyed).hasSize(1);

        assertThat(run(tenant, ECHO, "{}", Map.of()).success()).isTrue();
        assertThat(launcher.launches.get()).isEqualTo(2);
    }

    @Test
    void aSandboxThatDiesIsReplacedOnTheNextCall() throws Exception {
        pool(8, Duration.ofMinutes(5), 1000, Duration.ofSeconds(30));
        UUID tenant = UUID.randomUUID();
        run(tenant, ECHO, "{}", Map.of());
        launcher.processes.get(0).destroyForcibly().waitFor(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        assertThat(run(tenant, ECHO, "{}", Map.of()).success()).isTrue();
        assertThat(launcher.launches.get()).isEqualTo(2);
    }

    @Test
    void recyclesASandboxAfterTheConfiguredNumberOfInvocations() throws Exception {
        pool(8, Duration.ofMinutes(5), 2, Duration.ofSeconds(30));
        UUID tenant = UUID.randomUUID();
        for (int i = 0; i < 3; i++) run(tenant, ECHO, "{}", Map.of());
        assertThat(launcher.launches.get()).isEqualTo(2);
    }

    @Test
    void evictsTheOldestIdleSandboxWhenAtCapacity() throws Exception {
        pool(2, Duration.ofMinutes(5), 1000, Duration.ofSeconds(30));
        for (int i = 0; i < 3; i++) run(UUID.randomUUID(), ECHO, "{}", Map.of());
        assertThat(executor.activeSandboxes()).isEqualTo(2);
        assertThat(launcher.launches.get()).isEqualTo(3);
    }

    @Test
    void failsClearlyWhenEverySandboxIsBusy() throws Exception {
        pool(1, Duration.ofMinutes(5), 1000, Duration.ofSeconds(30));
        List<java.util.concurrent.CompletionStage<NodeExecutionResult>> inFlight = new ArrayList<>();
        inFlight.add(executor.execute(new NodeExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), SLOW, "handler", "{}", Map.of(), List.of(), UUID.randomUUID()), l -> { }));

        NodeExecutionResult rejected = run(UUID.randomUUID(), ECHO, "{}", Map.of());
        assertThat(rejected.success()).isFalse();
        assertThat(rejected.errorCode()).isEqualTo("SANDBOX_UNAVAILABLE");
        inFlight.get(0).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void stopsSandboxesThatStayIdle() throws Exception {
        pool(8, Duration.ofSeconds(1), 1000, Duration.ofSeconds(30));
        run(UUID.randomUUID(), ECHO, "{}", Map.of());
        assertThat(executor.activeSandboxes()).isEqualTo(1);

        long deadline = System.currentTimeMillis() + 8000;
        while (executor.activeSandboxes() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(200);
        assertThat(executor.activeSandboxes()).isZero();
    }

    @Test
    void reportsALaunchFailureAsAnErrorInsteadOfThrowing() throws Exception {
        pool(8, Duration.ofMinutes(5), 1000, Duration.ofSeconds(30));
        launcher.failToLaunch = true;
        NodeExecutionResult result = run(UUID.randomUUID(), ECHO, "{}", Map.of());
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("SANDBOX_UNAVAILABLE");
    }
}
