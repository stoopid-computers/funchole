package com.funchole.backend.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.artifact.ArtifactReference;
import com.funchole.backend.artifact.ArtifactStore;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Runtime Worker's IPC server. Listens on a Unix Domain Socket, accepts
 * persistent Dispatcher connections, decodes INVOKE messages, validates and
 * deduplicates them by {@code executionId}, responds ACCEPTED promptly, and
 * then executes the pinned artifact through a persistent {@link NodeExecutor}
 * before writing the real RESULT or ERROR.
 *
 * This is not a generic Function execution engine: for this milestone it
 * only supports {@code runtimeType=NODE} and {@code componentType} in
 * {@link #SUPPORTED_COMPONENT_TYPES} - FUNCTION, RESPONSE, and MIDDLEWARE all
 * execute identically here (resolve artifact by componentId/componentVersionId,
 * invoke its handler); the Dispatcher alone decides what a step's completion
 * means (e.g. RESPONSE ending the whole invocation). It never queries the
 * FuncHole database and never consumes JetStream - the Dispatcher remains the
 * sole global coordinator.
 *
 * Idempotency is in-memory and per-process only; a worker restart loses all
 * dedup state (documented limitation).
 */
public final class RuntimeWorkerServer implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeWorkerServer.class);
    private static final Set<String> SUPPORTED_COMPONENT_TYPES = Set.of("FUNCTION", "RESPONSE", "MIDDLEWARE");

    private final ServerSocketChannel serverChannel;
    private final Path socketPath;
    private final String runtimeInstanceId;
    private final String runtimeType;
    private final ArtifactStore artifactStore;
    private final NodeExecutor nodeExecutor;
    private final RuntimeInvokeValidator validator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<UUID, WorkerExecutionState> executionsByExecutionId = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    private RuntimeWorkerServer(
            ServerSocketChannel serverChannel,
            Path socketPath,
            String runtimeInstanceId,
            String runtimeType,
            ArtifactStore artifactStore,
            NodeExecutor nodeExecutor
    ) {
        this.serverChannel = serverChannel;
        this.socketPath = socketPath;
        this.runtimeInstanceId = runtimeInstanceId;
        this.runtimeType = runtimeType;
        this.artifactStore = artifactStore;
        this.nodeExecutor = nodeExecutor;
        this.validator = new RuntimeInvokeValidator(runtimeType);
    }

    /**
     * Binds the worker's socket. Removes a stale socket file left over from
     * an unclean shutdown, but refuses to bind (and does not touch the file)
     * if another process is actually listening on it.
     */
    public static RuntimeWorkerServer bind(
            Path socketPath,
            String runtimeInstanceId,
            String runtimeType,
            ArtifactStore artifactStore,
            NodeExecutor nodeExecutor
    ) throws IOException {
        prepareSocketPath(socketPath);
        ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
        return new RuntimeWorkerServer(
                serverChannel, socketPath, runtimeInstanceId, runtimeType, artifactStore, nodeExecutor);
    }

    /**
     * Starts the background accept loop. One daemon thread per accepted
     * connection; each connection is read sequentially.
     */
    public void start() {
        Thread acceptThread = new Thread(this::acceptLoop, "runtime-worker-accept-" + runtimeInstanceId);
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info(
                "Runtime worker ready: runtimeInstanceId={}, runtimeType={}, socket={}",
                runtimeInstanceId, runtimeType, socketPath
        );
    }

    public int acceptedCount() {
        return executionsByExecutionId.size();
    }

    public boolean hasAccepted(UUID executionId) {
        return executionsByExecutionId.containsKey(executionId);
    }

    @Override
    public void close() {
        running = false;
        try {
            serverChannel.close();
        } catch (IOException ignored) {
            // Best-effort close on shutdown.
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException exception) {
            logger.warn("Failed to remove socket file on shutdown: {}", socketPath);
        }
    }

    private static void prepareSocketPath(Path socketPath) throws IOException {
        Path parent = socketPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(socketPath)) {
            if (isLive(socketPath)) {
                throw new IllegalStateException(
                        "Refusing to bind: another live Runtime Worker is already listening on " + socketPath);
            }
            Files.delete(socketPath);
        }
    }

    private static boolean isLive(Path socketPath) {
        try (SocketChannel probe = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            probe.connect(UnixDomainSocketAddress.of(socketPath));
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private void acceptLoop() {
        while (running) {
            SocketChannel client;
            try {
                client = serverChannel.accept();
            } catch (IOException exception) {
                if (running) {
                    logger.warn("Runtime worker accept loop failed: {}", exception.getMessage());
                }
                return;
            }
            Thread handler = new Thread(() -> handleConnection(client), "runtime-worker-conn");
            handler.setDaemon(true);
            handler.start();
        }
    }

    private void handleConnection(SocketChannel client) {
        try (
                client;
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(Channels.newInputStream(client), StandardCharsets.UTF_8));
                OutputStream out = Channels.newOutputStream(client)
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!handleLine(line, out)) {
                    return;
                }
            }
        } catch (IOException exception) {
            logger.debug("Runtime worker connection closed: {}", exception.getMessage());
        }
    }

    /**
     * @return {@code false} if the connection should be closed (malformed or
     *         invalid INVOKE - no ACCEPTED is sent for it)
     */
    private boolean handleLine(String line, OutputStream out) throws IOException {
        RuntimeInvokeMessage message;
        try {
            message = objectMapper.readValue(line, RuntimeInvokeMessage.class);
        } catch (IOException exception) {
            logger.warn("Rejecting malformed INVOKE message: {}", exception.getMessage());
            return false;
        }

        String rejectionReason = validator.validate(message);
        if (rejectionReason != null) {
            logger.warn("Rejecting INVOKE executionId={}: {}", message.executionId(), rejectionReason);
            return false;
        }

        WorkerExecutionState executionState = new WorkerExecutionState(message);
        WorkerExecutionState existing = executionsByExecutionId.putIfAbsent(message.executionId(), executionState);
        boolean firstAcceptance = existing == null;
        WorkerExecutionState state = firstAcceptance ? executionState : existing;

        if (firstAcceptance) {
            logger.info(
                    "Artifact execution accepted: executionId={}, componentVersionId={}, runtimeType={}",
                    message.executionId(),
                    message.payload().componentVersionId(),
                    message.payload().runtimeType()
            );
        } else {
            logger.debug("Duplicate INVOKE for already-accepted executionId={}", message.executionId());
        }

        boolean terminalAlreadyDelivered;
        synchronized (state) {
            state.addReceiver(out);
            terminalAlreadyDelivered = state.terminalMessage() != null;
        }

        writeJson(out, RuntimeAcceptedMessage.of(message.executionId()));
        if (firstAcceptance) {
            executeArtifact(state);
        } else if (terminalAlreadyDelivered) {
            // This connection arrived after the execution already completed:
            // replay the immutable terminal message instead of waiting.
            RuntimeTerminalMessage replayed = state.terminalMessage();
            try {
                writeJson(out, replayed);
            } catch (IOException exception) {
                logger.warn("Failed to write replayed terminal runtime message for executionId={}: {}",
                        message.executionId(), exception.getMessage());
            }
        } else {
            // Duplicate INVOKE for an in-flight execution: this connection is
            // now registered as a delivery target and will receive the
            // terminal RESULT/ERROR when the artifact finishes.
        }
        return true;
    }

    private void executeArtifact(WorkerExecutionState state) {
        RuntimeInvokeMessage message = state.message();
        RuntimeInvokePayload payload = message.payload();

        String componentType = payload.componentType() == null ? "" : payload.componentType().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_COMPONENT_TYPES.contains(componentType)) {
            distributeTerminal(state, RuntimeTerminalMessage.error(
                    message.executionId(),
                    "UNSUPPORTED_COMPONENT_TYPE",
                    "Runtime Worker only executes " + runtimeType.toUpperCase(Locale.ROOT) + " " + SUPPORTED_COMPONENT_TYPES
                            + " components; got " + payload.componentType()
            ));
            return;
        }

        Optional<ArtifactReference> artifact = artifactStore.resolve(payload.componentId(), payload.componentVersionId());
        if (artifact.isEmpty()) {
            distributeTerminal(state, RuntimeTerminalMessage.error(
                    message.executionId(),
                    "ARTIFACT_NOT_FOUND",
                    "No artifact registered for componentVersionId=" + payload.componentVersionId()
            ));
            return;
        }
        logger.info(
                "Artifact resolved: executionId={}, componentVersionId={}, artifactPath={}",
                message.executionId(), payload.componentVersionId(), artifact.get().artifactPath()
        );

        NodeExecutionRequest nodeRequest = new NodeExecutionRequest(
                message.executionId(),
                payload.componentId(),
                payload.componentVersionId(),
                artifact.get().artifactPath(),
                artifact.get().handler(),
                payload.input(),
                payload.environment(),
                payload.databases(),
                payload.tenantId()
        );

        nodeExecutor.execute(nodeRequest, nodeLogMessage -> distributeLog(state, nodeLogMessage))
                .whenComplete((result, failure) -> {
                    RuntimeTerminalMessage terminalMessage = failure != null
                            ? RuntimeTerminalMessage.error(message.executionId(), "NODE_EXECUTOR_UNAVAILABLE", failure.getMessage())
                            : RuntimeTerminalMessage.from(result);
                    distributeTerminal(state, terminalMessage);
                });
    }

    /**
     * Delivers one line of runtime console output to every connection
     * currently registered for this execution - best-effort, like
     * {@link #distributeTerminal}, but never marks anything terminal and can
     * fire any number of times before it.
     */
    private void distributeLog(WorkerExecutionState state, NodeLogMessage nodeLogMessage) {
        RuntimeLogMessage logMessage = RuntimeLogMessage.from(nodeLogMessage);
        for (OutputStream target : List.copyOf(state.receivers())) {
            try {
                writeJson(target, logMessage);
            } catch (IOException exception) {
                logger.debug(
                        "Failed to write LOG runtime message for executionId={} to a connection: {}",
                        logMessage.executionId(), exception.getMessage()
                );
            }
        }
    }

    /**
     * Marks the execution terminal exactly once and delivers the terminal
     * message to every registered connection output. Writes are best-effort:
     * a stream belonging to a disconnected connection is skipped so the
     * remaining registered connections still receive the message.
     *
     * Terminal state lives on {@link WorkerExecutionState}, keyed by
     * executionId only - never on the connection that originated the
     * INVOKE.
     */
    private void distributeTerminal(WorkerExecutionState state, RuntimeTerminalMessage terminalMessage) {
        List<OutputStream> targets;
        synchronized (state) {
            if (!state.complete(terminalMessage)) {
                return;
            }
            targets = List.copyOf(state.receivers());
        }
        logger.info(
                "Runtime execution completed: executionId={}, type={}, connectionTargets={}",
                terminalMessage.executionId(),
                terminalMessage.type(),
                targets.size()
        );
        for (OutputStream target : targets) {
            try {
                writeJson(target, terminalMessage);
            } catch (IOException exception) {
                logger.warn(
                        "Failed to write terminal runtime message for executionId={} to a connection: {}",
                        terminalMessage.executionId(), exception.getMessage()
                );
            }
        }
    }

    private void writeJson(OutputStream out, Object message) throws IOException {
        synchronized (out) {
            String json = objectMapper.writeValueAsString(message);
            out.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static final class WorkerExecutionState {
        private final RuntimeInvokeMessage message;
        private final CopyOnWriteArrayList<OutputStream> receivers = new CopyOnWriteArrayList<>();
        private volatile RuntimeTerminalMessage terminalMessage;

        private WorkerExecutionState(RuntimeInvokeMessage message) {
            this.message = message;
        }

        private RuntimeInvokeMessage message() {
            return message;
        }

        private List<OutputStream> receivers() {
            return receivers;
        }

        private void addReceiver(OutputStream out) {
            receivers.addIfAbsent(out);
        }

        private RuntimeTerminalMessage terminalMessage() {
            return terminalMessage;
        }

        private boolean complete(RuntimeTerminalMessage terminalMessage) {
            if (this.terminalMessage != null) {
                return false;
            }
            this.terminalMessage = terminalMessage;
            return true;
        }
    }
}
