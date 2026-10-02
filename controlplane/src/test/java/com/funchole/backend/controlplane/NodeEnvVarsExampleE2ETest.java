package com.funchole.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.mcp.FunctionExampleFixtures;
import com.funchole.backend.controlplane.repository.AppDomainRepository;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.GatewayRepository;
import com.funchole.backend.dispatcher.ExecutionPlanner;
import com.funchole.backend.dispatcher.FunctionSecretReader;
import com.funchole.backend.dispatcher.InvocationDispatcher;
import com.funchole.backend.dispatcher.IpcRuntimeExecutionGateway;
import com.funchole.backend.dispatcher.JdbcFunctionVersionDatabaseResolver;
import com.funchole.backend.dispatcher.JdbcFunctionVersionEnvironmentResolver;
import com.funchole.backend.dispatcher.JdbcInvocationStepExecutionRegistry;
import com.funchole.backend.dispatcher.NoopInvocationStepExecutionLogRegistry;
import com.funchole.backend.invocation.JdbcInvocationRegistry;
import com.funchole.backend.invocation.NatsJetStreamInvocationEventPublisher;
import com.funchole.backend.artifact.ArtifactStore;
import com.funchole.backend.artifact.S3ArtifactStore;
import com.funchole.backend.artifact.S3ArtifactStoreConfig;
import com.funchole.backend.runtime.CachedArtifactStore;
import com.funchole.backend.runtime.FilesystemArtifactCache;
import com.funchole.backend.runtime.PersistentNodeExecutor;
import com.funchole.backend.runtime.RuntimeWorkerServer;
import com.funchole.backend.runtimeregistry.InMemoryRuntimeRegistry;
import com.funchole.backend.runtimeregistry.RuntimeInstance;
import com.funchole.backend.runtimeregistry.RuntimeInstanceStatus;
import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves {@link FunctionExampleFixtures#NODE_ENV_VARS_SOURCE} - the exact content
 * {@code get_function_example} returns for the NODE_ENV_VARS scenario - is a real, working
 * example: sets a FunctionVersion env var through the real {@code PUT .../config/env/{key}}
 * endpoint, deploys it, invokes it through a real in-process Dispatcher and Runtime Worker
 * (spawning a real {@code node} child process), and asserts the value set on
 * {@code process.env.GREETING} round-trips into the response body - the same mechanism a coding
 * agent has no other way to discover, since neither the runtime's invocation context nor any
 * other example mentions {@code process.env} anywhere.
 *
 * <p>Reuses {@link NodeDatabaseExampleE2ETest}'s in-process architecture, but wires the real
 * {@link JdbcFunctionVersionEnvironmentResolver} instead of a no-op, since this test is
 * specifically about the environment-resolution path that resolver implements.
 *
 * <p>Tagged {@code e2e}, same reasons as {@link NodeDatabaseExampleE2ETest}: requires
 * {@code node} on PATH and Docker, heavier than the default suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("e2e")
class NodeEnvVarsExampleE2ETest {

    private static final String BUCKET_NAME = "funchole-e2e-env-vars-example";
    private static final String RUNTIME_INSTANCE_ID = "runtime-node-env-vars-example-1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("funchole")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> nats = new GenericContainer<>(DockerImageName.parse("nats:2.14.6-alpine"))
            .withExposedPorts(4222)
            .withCommand("-js", "-sd", "/tmp/nats/jetstream");

    @Container
    static S3TestContainer minio = new S3TestContainer();

    @TempDir
    static Path artifactCacheRoot;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.artifact.endpoint", minio::getS3URL);
        registry.add("app.artifact.bucket", () -> BUCKET_NAME);
        registry.add("app.artifact.access-key", minio::getUserName);
        registry.add("app.artifact.secret-key", minio::getPassword);
        registry.add("app.artifact.path-style-access", () -> true);
        registry.add("app.nats.url", () -> "nats://" + nats.getHost() + ":" + nats.getMappedPort(4222));
    }

    private static HikariDataSource infraDataSource;
    private static Connection natsConnection;
    private static PersistentNodeExecutor nodeExecutor;
    private static RuntimeWorkerServer runtimeWorkerServer;
    private static InvocationDispatcher dispatcher;
    private static IpcRuntimeExecutionGateway executionGateway;
    private static Thread dispatcherLoopThread;
    private static final AtomicBoolean dispatcherRunning = new AtomicBoolean(true);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppDomainRepository appDomainRepository;

    @Autowired
    private GatewayRepository gatewayRepository;

    @BeforeAll
    static void startRealStack() throws Exception {
        createBucket();

        infraDataSource = new HikariDataSource(hikariConfig());
        natsConnection = Nats.connect("nats://" + nats.getHost() + ":" + nats.getMappedPort(4222));

        Path socketPath = Path.of("/tmp/fh-e2e-env-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
        nodeExecutor = PersistentNodeExecutor.start(
                "node", Path.of("../runtime/node/executor.mjs").toAbsolutePath());
        ArtifactStore artifactStore = new CachedArtifactStore(
                new FilesystemArtifactCache(artifactCacheRoot, "NODE"),
                new S3ArtifactStore("NODE", s3ArtifactStoreConfig())
        );
        runtimeWorkerServer = RuntimeWorkerServer.bind(socketPath, RUNTIME_INSTANCE_ID, "NODE", artifactStore, nodeExecutor);
        runtimeWorkerServer.start();

        InMemoryRuntimeRegistry runtimeRegistry = new InMemoryRuntimeRegistry();
        runtimeRegistry.register(new RuntimeInstance(RUNTIME_INSTANCE_ID, "NODE", RuntimeInstanceStatus.AVAILABLE, 4, 0, socketPath.toString()));
        executionGateway = new IpcRuntimeExecutionGateway(Duration.ofMillis(3000));
        // No secret is ever set in this test (GREETING is a plain env var), so this reader is
        // never actually called - only present because the resolver's constructor requires one.
        FunctionSecretReader unusedSecretReader = secretRef -> {
            throw new IllegalStateException("No secret should be read by this test, got: " + secretRef);
        };
        dispatcher = new InvocationDispatcher(
                natsConnection,
                new JdbcInvocationRegistry(infraDataSource, new NatsJetStreamInvocationEventPublisher(natsConnection)),
                new JdbcInvocationStepExecutionRegistry(infraDataSource),
                runtimeRegistry,
                new ExecutionPlanner(),
                executionGateway,
                new JdbcFunctionVersionEnvironmentResolver(infraDataSource, unusedSecretReader),
                new JdbcFunctionVersionDatabaseResolver(infraDataSource, secretRef -> {
                    throw new IllegalStateException("No database is attached in this test, got: " + secretRef);
                }),
                new NoopInvocationStepExecutionLogRegistry()
        );
        dispatcherLoopThread = new Thread(() -> {
            while (dispatcherRunning.get()) {
                dispatcher.processNext(Duration.ofMillis(200));
            }
        }, "e2e-env-dispatcher-loop");
        dispatcherLoopThread.setDaemon(true);
        dispatcherLoopThread.start();
    }

    @AfterAll
    static void stopRealStack() {
        dispatcherRunning.set(false);
        if (dispatcherLoopThread != null) {
            dispatcherLoopThread.interrupt();
        }
        if (dispatcher != null) {
            dispatcher.close();
        }
        if (executionGateway != null) {
            executionGateway.close();
        }
        if (runtimeWorkerServer != null) {
            runtimeWorkerServer.close();
        }
        if (nodeExecutor != null) {
            nodeExecutor.close();
        }
        if (natsConnection != null) {
            try {
                natsConnection.close();
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        if (infraDataSource != null) {
            infraDataSource.close();
        }
    }

    @Test
    void nodeEnvVarsExampleReadsARealFunctionVersionEnvVar() throws Exception {
        String token = obtainToken();

        String functionId = createFunction(token);
        String versionId = createDraftFunctionVersion(token, functionId);
        submitSource(token, functionId, versionId);
        setEnvVar(token, functionId, versionId, "GREETING", "hello from the example");
        deployAndAssertReady(token, functionId, versionId);

        String flowId = createFlow(token);
        String flowVersionId = createDraftFlowVersion(token, flowId);
        addResponseStep(token, flowId, flowVersionId, functionId, versionId);

        MvcResult invokeResult = mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/invoke", flowId, flowVersionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        String invocationId = JsonPath.read(invokeResult.getResponse().getContentAsString(), "$.data.invocationId");

        Map<String, Object> inspection = pollUntilTerminal(token, invocationId);
        assertThat(inspection.get("status")).isEqualTo("COMPLETED");
        String resultJson = (String) inspection.get("result");
        assertThat((Integer) JsonPath.read(resultJson, "$.status")).isEqualTo(200);
        assertThat((String) JsonPath.read(resultJson, "$.body.greeting")).isEqualTo("hello from the example");
    }

    private Map<String, Object> pollUntilTerminal(String token, String invocationId) throws Exception {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            MvcResult result = mockMvc.perform(get("/api/v1/invocations/{invocationId}", invocationId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            Map<String, Object> data = JsonPath.read(result.getResponse().getContentAsString(), "$.data");
            if (!"PENDING".equals(data.get("status"))) {
                return data;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Invocation " + invocationId + " did not reach a terminal status within the deadline");
    }

    private static void createBucket() {
        S3Client s3Client = S3Client.builder()
                .endpointOverride(URI.create(minio.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                .forcePathStyle(true)
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET_NAME).build());
        s3Client.close();
    }

    private static S3ArtifactStoreConfig s3ArtifactStoreConfig() {
        return new S3ArtifactStoreConfig(
                URI.create(minio.getS3URL()), BUCKET_NAME, minio.getUserName(), minio.getPassword(), "us-east-1", true);
    }

    private static HikariConfig hikariConfig() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setDriverClassName("org.postgresql.Driver");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setPoolName("e2e-env-example-infra-pool");
        return config;
    }

    private String obtainToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "admin",
                                  "password": "admin12345"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.accessToken");
    }

    private String createFunction(String token) throws Exception {
        String functionKey = "fn_env_example_" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/v1/functions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "functionKey": "%s",
                                  "name": "Env Vars Example Function",
                                  "runtime": "NODE"
                                }
                                """.formatted(functionKey)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private String createDraftFunctionVersion(String token, String functionId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/functions/{functionId}/versions", functionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private void submitSource(String token, String functionId, String versionId) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", FunctionExampleFixtures.NODE_ENV_VARS_ENTRYPOINT, "text/javascript",
                FunctionExampleFixtures.NODE_ENV_VARS_SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/functions/{functionId}/versions/{versionId}/source", functionId, versionId)
                        .file(file)
                        .param("entrypoint", FunctionExampleFixtures.NODE_ENV_VARS_ENTRYPOINT)
                        .param("handler", FunctionExampleFixtures.NODE_ENV_VARS_HANDLER)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    private void setEnvVar(String token, String functionId, String versionId, String key, String value) throws Exception {
        mockMvc.perform(put("/api/v1/functions/{functionId}/versions/{versionId}/config/env/{key}", functionId, versionId, key)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"value": "%s"}
                                """.formatted(value)))
                .andExpect(status().isOk());
    }

    private void deployAndAssertReady(String token, String functionId, String versionId) throws Exception {
        mockMvc.perform(post("/api/v1/functions/{functionId}/versions/{versionId}/deploy", functionId, versionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            MvcResult result = mockMvc.perform(get("/api/v1/functions/{functionId}/versions/{versionId}", functionId, versionId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            String status = JsonPath.read(result.getResponse().getContentAsString(), "$.data.status");
            if ("READY".equals(status)) {
                return;
            }
            if (!"PUBLISHING".equals(status)) {
                throw new AssertionError("FunctionVersion " + versionId + " deploy ended in unexpected status: " + status);
            }
            Thread.sleep(200);
        }
        throw new AssertionError("FunctionVersion " + versionId + " did not reach READY within the deadline");
    }

    private String createFlow(String token) throws Exception {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        AppDomain domain = appDomainRepository.save(
                AppDomain.create(admin, "env-example-" + UUID.randomUUID() + ".example.com", "verify-me", DomainStatus.VERIFIED));
        Gateway gateway = gatewayRepository.save(
                Gateway.create(admin, domain, "Env Example Gateway", "eneg" + System.nanoTime() % 100000, "test gateway", GatewayStatus.ACTIVE));

        String flowKey = "flw_env_example_" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "flowKey": "%s",
                                  "name": "Env Vars Example Flow",
                                  "description": "created by NodeEnvVarsExampleE2ETest",
                                  "gatewayId": "%s",
                                  "httpMethod": "GET",
                                  "path": "/env-example-%s"
                                }
                                """.formatted(flowKey, gateway.getId(), flowKey)))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private String createDraftFlowVersion(String token, String flowId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/flows/{flowId}/versions", flowId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runtime\":\"NODE\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private void addResponseStep(String token, String flowId, String flowVersionId, String functionId, String functionVersionId) throws Exception {
        mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/steps", flowId, flowVersionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "stepKey": "respond",
                                  "componentType": "RESPONSE",
                                  "position": 10,
                                  "componentId": "%s",
                                  "componentVersionId": "%s"
                                }
                                """.formatted(functionId, functionVersionId)))
                .andExpect(status().isOk());
    }
}
