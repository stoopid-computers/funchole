package com.funchole.backend.runtime;

import com.funchole.backend.artifact.ArtifactStore;
import com.funchole.backend.artifact.LocalArtifactStore;
import com.funchole.backend.artifact.RemoteArtifactStore;
import com.funchole.backend.artifact.S3ArtifactStore;
import com.funchole.backend.artifact.S3ArtifactStoreConfig;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RuntimeWorkerMain {
    private static final Logger logger = LoggerFactory.getLogger(RuntimeWorkerMain.class);

    private RuntimeWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path socketPath = Path.of(readString("RUNTIME_WORKER_SOCKET_PATH", "/tmp/funchole/runtime-node-dev-1.sock"));
        String runtimeInstanceId = readString("RUNTIME_INSTANCE_ID", "runtime-node-dev-1");
        String runtimeType = readString("RUNTIME_TYPE", "NODE");
        Path artifactsRoot = Path.of(readString("ARTIFACT_DIR", "artifacts/dev"));
        Path artifactCacheRoot = Path.of(readString("ARTIFACT_CACHE_DIR", "/tmp/funchole/artifact-cache"));
        String artifactStoreType = readString("ARTIFACT_STORE_TYPE", "local");
        String nodeCommand = readString("NODE_COMMAND", "node");
        Path nodeExecutorScript = Path.of(readString("NODE_EXECUTOR_SCRIPT_PATH", "node/executor.mjs"));

        IsolationMode isolation = IsolationMode.parseDefault(System.getenv("RUNTIME_ISOLATION"));
        Set<String> sandboxTenants = IsolationMode.parseAllowList(System.getenv("RUNTIME_ISOLATION_TENANTS"));
        IsolationMode.validate(isolation, sandboxTenants, "true".equalsIgnoreCase(System.getenv("RUNTIME_ISOLATION_ALLOW_MIXED")));
        logger.info("Runtime isolation: default={}, sandbox tenants={}", isolation.name().toLowerCase(), sandboxTenants.size());

        ArtifactStore artifactStore = createArtifactStore(artifactStoreType, artifactsRoot, artifactCacheRoot, runtimeType);
        // Each executor only starts when something can route to it, so a pure-sandbox runtime never
        // runs the shared Node process (and vice versa).
        PersistentNodeExecutor legacyExecutor = isolation == IsolationMode.LEGACY
                ? PersistentNodeExecutor.start(nodeCommand, nodeExecutorScript)
                : null;
        SandboxNodeExecutor sandboxExecutor = isolation == IsolationMode.SANDBOX || !sandboxTenants.isEmpty()
                ? new SandboxNodeExecutor(
                        new DockerSandboxLauncher(Path.of(readString("SANDBOX_LAUNCHER", "/opt/funchole/sandbox/launch.sh")), System.getenv()),
                        SandboxConfig.from(System.getenv()))
                : null;
        IsolationRoutingNodeExecutor nodeExecutor = new IsolationRoutingNodeExecutor(isolation, sandboxTenants, legacyExecutor, sandboxExecutor);

        RuntimeWorkerServer server = RuntimeWorkerServer.bind(
                socketPath,
                runtimeInstanceId,
                runtimeType,
                artifactStore,
                nodeExecutor
        );
        server.start();

        // No HTTP listener exists on this service to give a container
        // healthcheck something to poll (it only speaks the IPC protocol
        // over socketPath), so touch a file on a fixed interval instead - a
        // hung/deadlocked JVM (still technically "running" as a process)
        // stops updating it, giving an orchestrator a real liveness signal.
        Path heartbeatFile = Path.of(readString("RUNTIME_HEARTBEAT_FILE", "/tmp/funchole/runtime-heartbeat"));
        ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "runtime-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeatExecutor.scheduleAtFixedRate(() -> writeHeartbeat(heartbeatFile), 0, 5, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            heartbeatExecutor.shutdownNow();
            server.close();
            nodeExecutor.close();
        }));

        logger.info("Runtime worker running. Press Ctrl+C to stop.");
        new CountDownLatch(1).await();
    }

    private static void writeHeartbeat(Path heartbeatFile) {
        try {
            Files.createDirectories(heartbeatFile.getParent());
            Files.writeString(heartbeatFile, Instant.now().toString());
        } catch (IOException exception) {
            logger.warn("Failed to write runtime worker heartbeat file {}", heartbeatFile, exception);
        }
    }

    private static ArtifactStore createArtifactStore(
            String artifactStoreType,
            Path artifactsRoot,
            Path artifactCacheRoot,
            String runtimeType
    ) {
        if ("s3".equalsIgnoreCase(artifactStoreType)) {
            ArtifactCache cache = new FilesystemArtifactCache(artifactCacheRoot, runtimeType);
            RemoteArtifactStore remoteStore = new S3ArtifactStore(runtimeType, new S3ArtifactStoreConfig(
                    URI.create(readRequiredString("S3_ARTIFACT_ENDPOINT")),
                    readRequiredString("S3_ARTIFACT_BUCKET"),
                    readRequiredString("S3_ARTIFACT_ACCESS_KEY"),
                    readRequiredString("S3_ARTIFACT_SECRET_KEY"),
                    readString("S3_ARTIFACT_REGION", "us-east-1"),
                    readBoolean("S3_ARTIFACT_PATH_STYLE_ACCESS", true)
            ));
            return new CachedArtifactStore(cache, remoteStore);
        }
        if ("local".equalsIgnoreCase(artifactStoreType)) {
            return new LocalArtifactStore(artifactsRoot, runtimeType);
        }
        throw new IllegalArgumentException("Unsupported ARTIFACT_STORE_TYPE: " + artifactStoreType);
    }

    private static String readString(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String readRequiredString(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static boolean readBoolean(String name, boolean fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }
}
