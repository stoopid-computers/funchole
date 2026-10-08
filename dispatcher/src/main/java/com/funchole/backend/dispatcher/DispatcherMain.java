package com.funchole.backend.dispatcher;

import com.funchole.backend.invocation.InvocationMessagingConfig;
import com.funchole.backend.invocation.JdbcInvocationRegistry;
import com.funchole.backend.invocation.NatsJetStreamInvocationEventPublisher;
import com.funchole.backend.runtimeregistry.InMemoryRuntimeRegistry;
import com.funchole.backend.runtimeregistry.JdbcRuntimeRegistry;
import com.funchole.backend.runtimeregistry.RuntimeInstance;
import com.funchole.backend.runtimeregistry.RuntimeInstanceStatus;
import com.funchole.backend.runtimeregistry.RuntimeRegistry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DispatcherMain {
    private static final Logger logger = LoggerFactory.getLogger(DispatcherMain.class);

    private DispatcherMain() {
    }

    public static void main(String[] args) throws Exception {
        DataSource dataSource = createDataSource();
        Connection natsConnection = Nats.connect(readString("NATS_URL", "nats://localhost:4222"));
        IpcRuntimeExecutionGateway executionGateway = new IpcRuntimeExecutionGateway(
                Duration.ofMillis(readInt("RUNTIME_IPC_ACCEPT_TIMEOUT_MS", 3000))
        );
        FunctionSecretReader secretReader = new OpenBaoFunctionSecretReader(
                readString("BAO_ADDR", "http://localhost:8200"),
                readString("BAO_TOKEN", "root")
        );
        InvocationDispatcher dispatcher = new InvocationDispatcher(
                natsConnection,
                new JdbcInvocationRegistry(dataSource, new NatsJetStreamInvocationEventPublisher(natsConnection)),
                new JdbcInvocationStepExecutionRegistry(dataSource),
                createRuntimeRegistry(dataSource),
                new ExecutionPlanner(),
                executionGateway,
                new JdbcFunctionVersionEnvironmentResolver(dataSource, secretReader),
                new JdbcFunctionVersionDatabaseResolver(dataSource, secretReader),
                new JdbcInvocationStepExecutionLogRegistry(dataSource),
                new JdbcTenantResolver(dataSource)
        );
        Duration pollTimeout = Duration.ofMillis(readInt("DISPATCHER_POLL_TIMEOUT_MS", 1000));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            dispatcher.close();
            executionGateway.close();
            try {
                natsConnection.close();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
            if (dataSource instanceof HikariDataSource hikariDataSource) {
                hikariDataSource.close();
            }
        }));

        logger.info(
                "Dispatcher started. stream={}, subject={}, durable={}",
                InvocationMessagingConfig.STREAM_NAME,
                InvocationMessagingConfig.INVOCATION_READY_SUBJECT,
                InvocationMessagingConfig.DISPATCHER_DURABLE
        );

        Path heartbeatFile = Path.of(readString("DISPATCHER_HEARTBEAT_FILE", "/tmp/funchole/dispatcher-heartbeat"));
        while (!Thread.currentThread().isInterrupted()) {
            writeHeartbeat(heartbeatFile);
            dispatcher.processNext(pollTimeout);
        }
    }

    /**
     * Dispatcher has no HTTP listener, so a container healthcheck can't poll
     * an endpoint the way gateway/controlplane's do. Touching this file once
     * per poll cycle gives an orchestrator a liveness signal instead - a
     * stuck/deadlocked loop stops updating it, even though the process
     * itself is still technically running. Best-effort: a failure to write
     * it should never crash the dispatch loop itself.
     */
    private static void writeHeartbeat(Path heartbeatFile) {
        try {
            Files.createDirectories(heartbeatFile.getParent());
            Files.writeString(heartbeatFile, Instant.now().toString());
        } catch (IOException exception) {
            logger.warn("Failed to write dispatcher heartbeat file {}", heartbeatFile, exception);
        }
    }

    private static RuntimeRegistry createRuntimeRegistry(DataSource dataSource) {
        RuntimeRegistry runtimeRegistry = "memory".equalsIgnoreCase(readString("RUNTIME_REGISTRY_TYPE", "jdbc"))
                ? new InMemoryRuntimeRegistry()
                : new JdbcRuntimeRegistry(dataSource);
        runtimeRegistry.register(new RuntimeInstance(
                readString("DEV_RUNTIME_INSTANCE_ID", "runtime-node-dev-1"),
                readString("DEV_RUNTIME_TYPE", "NODE"),
                RuntimeInstanceStatus.AVAILABLE,
                readInt("DEV_RUNTIME_CAPACITY", 4),
                0,
                readString("DEV_RUNTIME_SOCKET_PATH", "/tmp/funchole/runtime-node-dev-1.sock")
        ));
        return runtimeRegistry;
    }

    private static DataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(readString("DB_URL", "jdbc:postgresql://localhost:5432/funchole"));
        config.setUsername(readString("DB_USERNAME", "funchole"));
        config.setPassword(readString("DB_PASSWORD", "funchole"));
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setPoolName("dispatcher-db-pool");
        return new HikariDataSource(config);
    }

    private static String readString(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int readInt(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value);
    }
}
