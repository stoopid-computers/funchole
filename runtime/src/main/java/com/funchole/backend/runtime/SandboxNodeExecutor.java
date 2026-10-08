package com.funchole.backend.runtime;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs tenant code in one isolated sandbox per tenant instead of the shared Node process.
 *
 * <p>It speaks exactly the same protocol as {@link PersistentNodeExecutor} (each sandbox is one
 * wrapped executor process), so what a function sees, logs and returns does not change; only where
 * it runs does. Sandboxes start on first use, are reused while warm, and are thrown away when idle,
 * after a fixed number of invocations, when they exceed the execution timeout, or when they die
 * (for example killed by a memory limit). Work with no known tenant shares a single sandbox.
 */
final class SandboxNodeExecutor implements NodeExecutor, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(SandboxNodeExecutor.class);
    static final String SHARED_TENANT = "shared";

    private final SandboxLauncher launcher;
    private final SandboxConfig config;
    private final Object lock = new Object();
    private final Map<String, Sandbox> sandboxes = new HashMap<>();
    private final ScheduledExecutorService scheduler;
    private final AtomicInteger sequence = new AtomicInteger();
    private volatile boolean closed;

    private static final class Sandbox {
        final String tenant;
        final String name;
        final PersistentNodeExecutor executor;
        int inFlight;
        int invocations;
        long lastUsedNanos = System.nanoTime();

        Sandbox(String tenant, String name, PersistentNodeExecutor executor) {
            this.tenant = tenant;
            this.name = name;
            this.executor = executor;
        }
    }

    SandboxNodeExecutor(SandboxLauncher launcher, SandboxConfig config) {
        this.launcher = launcher;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sandbox-reaper");
            thread.setDaemon(true);
            return thread;
        });
        long periodSeconds = Math.max(1, Math.min(30, config.idleTtl().toSeconds() / 2));
        scheduler.scheduleWithFixedDelay(this::reapIdle, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    @Override
    public CompletionStage<NodeExecutionResult> execute(NodeExecutionRequest request, Consumer<NodeLogMessage> onLog) {
        UUID executionId = request.executionId();
        Sandbox sandbox;
        try {
            sandbox = acquire(tenantKey(request));
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.completedFuture(NodeExecutionResult.failure(
                    executionId, "SANDBOX_UNAVAILABLE", "Could not start an isolated runtime: " + failure.getMessage()));
        }

        CompletableFuture<NodeExecutionResult> result = new CompletableFuture<>();
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            if (result.isDone()) {
                return;
            }
            logger.warn("Sandbox timeout, discarding: tenant={}, sandbox={}, executionId={}", sandbox.tenant, sandbox.name, executionId);
            timedOut.set(true);
            // Remove the sandbox first: once the caller sees the result, the pool already reflects it.
            discard(sandbox);
            result.complete(NodeExecutionResult.failure(executionId, "EXECUTION_TIMEOUT",
                    "The function ran longer than " + config.executionTimeout().toSeconds() + " seconds and was stopped."));
        }, config.executionTimeout().toMillis(), TimeUnit.MILLISECONDS);

        sandbox.executor.execute(request, onLog).whenComplete((outcome, failure) -> {
            timeout.cancel(false);
            if (timedOut.get()) {
                return; // killed by the timeout above; that error is the one the caller gets
            }
            if (failure != null) {
                // The sandbox process went away mid-call (out of memory, crash, killed).
                if (result.complete(NodeExecutionResult.failure(executionId, "SANDBOX_TERMINATED",
                        "The function was stopped: it ran out of memory or crashed."))) {
                    logger.warn("Sandbox terminated mid-execution: tenant={}, sandbox={}, executionId={}", sandbox.tenant, sandbox.name, executionId);
                }
                discard(sandbox);
            } else {
                result.complete(outcome);
                release(sandbox);
            }
        });
        return result;
    }

    private static String tenantKey(NodeExecutionRequest request) {
        return request.tenantId() == null ? SHARED_TENANT : request.tenantId().toString();
    }

    private Sandbox acquire(String tenant) throws IOException {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("sandbox executor is closed");
            }
            Sandbox sandbox = sandboxes.get(tenant);
            if (sandbox != null && !sandbox.executor.isAlive()) {
                removeLocked(sandbox);
                sandbox = null;
            }
            if (sandbox == null) {
                if (sandboxes.size() >= config.maxSandboxes()) {
                    evictOldestIdleLocked();
                }
                String name = "fh-sbx-" + tenant.substring(0, Math.min(8, tenant.length())) + "-" + sequence.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 6);
                sandbox = new Sandbox(tenant, name, PersistentNodeExecutor.fromProcess(launcher.launch(name)));
                sandboxes.put(tenant, sandbox);
                logger.info("Sandbox started: tenant={}, sandbox={}, active={}", tenant, name, sandboxes.size());
            }
            sandbox.inFlight++;
            sandbox.invocations++;
            sandbox.lastUsedNanos = System.nanoTime();
            return sandbox;
        }
    }

    private void release(Sandbox sandbox) {
        synchronized (lock) {
            sandbox.inFlight--;
            sandbox.lastUsedNanos = System.nanoTime();
            if (sandbox.inFlight == 0 && sandbox.invocations >= config.recycleAfterInvocations()) {
                logger.info("Recycling sandbox after {} invocations: tenant={}, sandbox={}", sandbox.invocations, sandbox.tenant, sandbox.name);
                removeLocked(sandbox);
            }
        }
    }

    private void discard(Sandbox sandbox) {
        synchronized (lock) {
            removeLocked(sandbox);
        }
    }

    private void removeLocked(Sandbox sandbox) {
        if (sandboxes.get(sandbox.tenant) == sandbox) {
            sandboxes.remove(sandbox.tenant);
        }
        sandbox.executor.close();
        launcher.destroy(sandbox.name);
    }

    private void evictOldestIdleLocked() {
        Sandbox oldest = null;
        for (Sandbox candidate : sandboxes.values()) {
            if (candidate.inFlight == 0 && (oldest == null || candidate.lastUsedNanos < oldest.lastUsedNanos)) {
                oldest = candidate;
            }
        }
        if (oldest == null) {
            throw new IllegalStateException("all " + config.maxSandboxes() + " sandboxes are busy");
        }
        logger.info("Evicting idle sandbox to make room: tenant={}, sandbox={}", oldest.tenant, oldest.name);
        removeLocked(oldest);
    }

    private void reapIdle() {
        long idleNanos = config.idleTtl().toNanos();
        synchronized (lock) {
            for (Sandbox sandbox : Map.copyOf(sandboxes).values()) {
                if (sandbox.inFlight == 0 && System.nanoTime() - sandbox.lastUsedNanos > idleNanos) {
                    logger.info("Stopping idle sandbox: tenant={}, sandbox={}", sandbox.tenant, sandbox.name);
                    removeLocked(sandbox);
                }
            }
        }
    }

    int activeSandboxes() {
        synchronized (lock) {
            return sandboxes.size();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            for (Sandbox sandbox : Map.copyOf(sandboxes).values()) {
                removeLocked(sandbox);
            }
        }
        scheduler.shutdownNow();
    }
}
