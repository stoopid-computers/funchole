package com.funchole.backend.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link NodeExecutor} backed by one long-lived {@code node} process, kept
 * warm across many executions. A new process is never spawned per execution.
 *
 * Java writes are serialized with a lock; Node's own stdout write queue
 * already guarantees one JSON line is never interleaved with another, so no
 * corresponding lock is needed on that side (see {@code executor.mjs}).
 *
 * Correlation is strictly by {@code executionId} via a pending-futures map -
 * never by message order. If the process dies (stdout closes) or
 * {@link #close()} is called, every still-pending execution fails rather
 * than hanging forever.
 */
public final class PersistentNodeExecutor implements NodeExecutor, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(PersistentNodeExecutor.class);

    private final Process process;
    private final OutputStream stdin;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Object writeLock = new Object();
    private final Map<UUID, CompletableFuture<NodeExecutionResult>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<NodeLogMessage>> logListeners = new ConcurrentHashMap<>();
    private volatile boolean closed = false;

    private PersistentNodeExecutor(Process process) {
        this.process = process;
        this.stdin = process.getOutputStream();
        startReaderThread();
        startStderrDrain();
        logger.info("Node executor started: pid={}", process.pid());
    }

    /** Wraps an already-started process that speaks the executor protocol (e.g. a sandbox launcher). */
    static PersistentNodeExecutor fromProcess(Process process) {
        return new PersistentNodeExecutor(process);
    }

    public static PersistentNodeExecutor start(String nodeCommand, Path scriptPath) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(nodeCommand, scriptPath.toString());
        Process process = builder.start();
        return new PersistentNodeExecutor(process);
    }

    @Override
    public CompletionStage<NodeExecutionResult> execute(NodeExecutionRequest request, Consumer<NodeLogMessage> onLog) {
        CompletableFuture<NodeExecutionResult> newFuture = new CompletableFuture<>();
        CompletableFuture<NodeExecutionResult> existing = pending.putIfAbsent(request.executionId(), newFuture);
        if (existing != null) {
            return existing;
        }
        if (onLog != null) {
            logListeners.put(request.executionId(), onLog);
        }

        if (closed || !process.isAlive()) {
            pending.remove(request.executionId(), newFuture);
            logListeners.remove(request.executionId());
            newFuture.completeExceptionally(new IllegalStateException("Node executor process is not running"));
            return newFuture;
        }

        logger.info(
                "Node artifact execution started: executionId={}, componentVersionId={}",
                request.executionId(), request.componentVersionId()
        );
        try {
            writeExecute(NodeExecuteMessage.from(request));
        } catch (IOException exception) {
            pending.remove(request.executionId(), newFuture);
            logListeners.remove(request.executionId());
            newFuture.completeExceptionally(exception);
        }
        return newFuture;
    }

    public boolean isAlive() {
        return !closed && process.isAlive();
    }

    public long pid() {
        return process.pid();
    }

    @Override
    public void close() {
        closed = true;
        try {
            stdin.close();
        } catch (IOException ignored) {
            // Best-effort close on shutdown.
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        failAllPending(new IllegalStateException("Node executor is shutting down"));
    }

    private void writeExecute(NodeExecuteMessage message) throws IOException {
        String json = objectMapper.writeValueAsString(message) + "\n";
        synchronized (writeLock) {
            stdin.write(json.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        }
    }

    private void startReaderThread() {
        Thread reader = new Thread(this::readLoop, "node-executor-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void startStderrDrain() {
        Thread stderrDrain = new Thread(this::drainStderr, "node-executor-stderr");
        stderrDrain.setDaemon(true);
        stderrDrain.start();
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                handleLine(line);
            }
        } catch (IOException ignored) {
            // Process stdout closed/broken; handled by the finally block below.
        } finally {
            closed = true;
            logger.warn("Node executor process stdout closed (pid={}); failing pending executions", process.pid());
            failAllPending(new IllegalStateException("Node executor process terminated"));
        }
    }

    private void handleLine(String line) {
        String type;
        try {
            type = objectMapper.readTree(line).path("type").asText();
        } catch (IOException exception) {
            logger.warn("Discarding malformed Node executor message: {}", exception.getMessage());
            return;
        }

        if (NodeLogMessage.TYPE.equals(type)) {
            handleLog(line);
            return;
        }

        NodeTerminalMessage message;
        try {
            message = objectMapper.readValue(line, NodeTerminalMessage.class);
        } catch (IOException exception) {
            logger.warn("Discarding malformed Node terminal message: {}", exception.getMessage());
            return;
        }
        if (message.executionId() == null) {
            logger.warn("Discarding Node executor message with no executionId");
            return;
        }

        CompletableFuture<NodeExecutionResult> future = pending.remove(message.executionId());
        logListeners.remove(message.executionId());
        if (future == null) {
            logger.warn("Received Node executor result for unknown or already-completed executionId={}", message.executionId());
            return;
        }
        NodeExecutionResult result = message.toResult();
        logger.info(
                "Node artifact execution completed: executionId={}, success={}",
                result.executionId(), result.success()
        );
        future.complete(result);
    }

    private void handleLog(String line) {
        try {
            NodeLogMessage message = objectMapper.readValue(line, NodeLogMessage.class);
            logger.info(
                    "Function log: executionId={}, stream={}, message={}",
                    message.executionId(),
                    message.stream(),
                    message.message()
            );
            Consumer<NodeLogMessage> listener = logListeners.get(message.executionId());
            if (listener != null) {
                listener.accept(message);
            }
        } catch (IOException exception) {
            logger.warn("Discarding malformed Node log message: {}", exception.getMessage());
        }
    }

    private void drainStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("[node-executor stderr] {}", line);
            }
        } catch (IOException ignored) {
            // Process gone; nothing left to drain.
        }
    }

    private void failAllPending(Exception cause) {
        pending.forEach((executionId, future) -> future.completeExceptionally(cause));
        pending.clear();
        logListeners.clear();
    }
}
