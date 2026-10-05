package com.funchole.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.repository.AppDomainRepository;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.GatewayRepository;
import com.funchole.backend.dispatcher.ExecutionPlanner;
import com.funchole.backend.dispatcher.InvocationDispatcher;
import com.funchole.backend.dispatcher.IpcRuntimeExecutionGateway;
import com.funchole.backend.dispatcher.JdbcInvocationStepExecutionRegistry;
import com.funchole.backend.dispatcher.NoopFunctionVersionEnvironmentResolver;
import com.funchole.backend.dispatcher.JdbcFunctionVersionDatabaseResolver;
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
import java.nio.charset.StandardCharsets;
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
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the production incident this session fixed (2026-09-29): a single
 * Function whose handler never resolves must not be able to block every
 * other Function on the same Runtime Worker. Before executor.mjs's
 * EXECUTION_TIMEOUT_MS fix, the Runtime Worker's persistent Node process
 * processes executions one at a time (see executor.mjs's module header) with
 * no ceiling on how long one is allowed to take - a hung handler wedged the
 * queue forever, and every other tenant's traffic queued up behind it and
 * never ran. This deploys a Function that awaits a promise that never
 * settles, invokes it, then immediately invokes an unrelated normal Function
 * and asserts it still completes promptly - proving the queue was not
 * blocked - and separately asserts the hung invocation itself eventually
 * reaches a terminal FAILED state (not PENDING forever) once its own
 * timeout elapses.
 *
 * <p>Runs the spawned {@code node} process with a short
 * {@code RUNTIME_EXECUTION_TIMEOUT_MS} (see the new
 * {@link PersistentNodeExecutor#start(String, Path, Map)} overload) so this
 * stays fast instead of waiting on executor.mjs's real 30s default.
 *
 * <p>Reuses {@link NodeDatabaseExampleE2ETest}'s in-process direct-invoke
 * architecture. Tagged {@code e2e} for the same reasons: requires
 * {@code node} on PATH and Docker, heavier than the default suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("e2e")
class HungFunctionDoesNotBlockOtherInvocationsE2ETest {

    private static final String BUCKET_NAME = "funchole-e2e-hung-function";
    private static final String RUNTIME_INSTANCE_ID = "runtime-node-hung-function-1";
    private static final String TEST_EXECUTION_TIMEOUT_MS = "1000";

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
    static MinIOContainer minio = new MinIOContainer("minio/minio");

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

        Path socketPath = Path.of("/tmp/fh-e2e-hung-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
        nodeExecutor = PersistentNodeExecutor.start(
                "node",
                Path.of("../runtime/node/executor.mjs").toAbsolutePath(),
                Map.of("RUNTIME_EXECUTION_TIMEOUT_MS", TEST_EXECUTION_TIMEOUT_MS));
        ArtifactStore artifactStore = new CachedArtifactStore(
                new FilesystemArtifactCache(artifactCacheRoot, "NODE"),
                new S3ArtifactStore("NODE", s3ArtifactStoreConfig())
        );
        runtimeWorkerServer = RuntimeWorkerServer.bind(socketPath, RUNTIME_INSTANCE_ID, "NODE", artifactStore, nodeExecutor);
        runtimeWorkerServer.start();

        InMemoryRuntimeRegistry runtimeRegistry = new InMemoryRuntimeRegistry();
        // Deliberately >1: both invocations must be dispatcher-ACCEPTED
        // immediately with no capacity contention, so the only thing this
        // test can possibly be measuring is executor.mjs's own single Node
        // process choosing to move past the hung one - not dispatcher-level
        // reservation queuing or NATS redelivery backoff, which are separate,
        // already-working mechanisms this test isn't about.
        runtimeRegistry.register(new RuntimeInstance(RUNTIME_INSTANCE_ID, "NODE", RuntimeInstanceStatus.AVAILABLE, 2, 0, socketPath.toString()));
        executionGateway = new IpcRuntimeExecutionGateway(Duration.ofMillis(3000));
        dispatcher = new InvocationDispatcher(
                natsConnection,
                new JdbcInvocationRegistry(infraDataSource, new NatsJetStreamInvocationEventPublisher(natsConnection)),
                new JdbcInvocationStepExecutionRegistry(infraDataSource),
                runtimeRegistry,
                new ExecutionPlanner(),
                executionGateway,
                new NoopFunctionVersionEnvironmentResolver(),
                new JdbcFunctionVersionDatabaseResolver(infraDataSource, secretRef -> {
                    throw new IllegalStateException("No database is attached in this test, got: " + secretRef);
                }),
                new NoopInvocationStepExecutionLogRegistry()
        );
        dispatcherLoopThread = new Thread(() -> {
            while (dispatcherRunning.get()) {
                dispatcher.processNext(Duration.ofMillis(200));
            }
        }, "e2e-hung-function-dispatcher-loop");
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
    void anUnrelatedInvocationStillCompletesWhileAnEarlierOneIsHung() throws Exception {
        String token = obtainToken();

        String hungFunctionId = createFunction(token, "fn_hang_test");
        String hungVersionId = createDraftFunctionVersion(token, hungFunctionId);
        submitSource(token, hungFunctionId, hungVersionId,
                "export async function handler() { return await new Promise(() => {}); }");
        deployAndAssertReady(token, hungFunctionId, hungVersionId);
        String hungFlowId = createFlow(token, "hang");
        String hungFlowVersionId = createDraftFlowVersion(token, hungFlowId);
        addResponseStep(token, hungFlowId, hungFlowVersionId, hungFunctionId, hungVersionId);

        String normalFunctionId = createFunction(token, "fn_normal_test");
        String normalVersionId = createDraftFunctionVersion(token, normalFunctionId);
        submitSource(token, normalFunctionId, normalVersionId,
                "export async function handler(input) { return { status: 200, body: { ok: true } }; }");
        deployAndAssertReady(token, normalFunctionId, normalVersionId);
        String normalFlowId = createFlow(token, "normal");
        String normalFlowVersionId = createDraftFlowVersion(token, normalFlowId);
        addResponseStep(token, normalFlowId, normalFlowVersionId, normalFunctionId, normalVersionId);

        String hungInvocationId = invoke(token, hungFlowId, hungFlowVersionId);
        // Give the dispatcher a moment to actually hand the hung execution
        // off to the Runtime Worker before firing the second one, so this
        // genuinely proves the second isn't blocked behind it - not just a
        // race where both happened to be dispatched before either started.
        Thread.sleep(200);
        String normalInvocationId = invoke(token, normalFlowId, normalFlowVersionId);

        Map<String, Object> normalResult = pollUntilTerminal(token, normalInvocationId, Duration.ofSeconds(5));
        assertThat(normalResult.get("status")).isEqualTo("COMPLETED");

        Map<String, Object> hungResult = pollUntilTerminal(token, hungInvocationId, Duration.ofSeconds(5));
        assertThat(hungResult.get("status")).isEqualTo("FAILED");
    }

    private String invoke(String token, String flowId, String flowVersionId) throws Exception {
        MvcResult invokeResult = mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/invoke", flowId, flowVersionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(invokeResult.getResponse().getContentAsString(), "$.data.invocationId");
    }

    private Map<String, Object> pollUntilTerminal(String token, String invocationId, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            MvcResult result = mockMvc.perform(get("/api/v1/invocations/{invocationId}", invocationId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            Map<String, Object> data = JsonPath.read(result.getResponse().getContentAsString(), "$.data");
            if (!"PENDING".equals(data.get("status"))) {
                return data;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Invocation " + invocationId + " did not reach a terminal status within " + timeout);
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
        config.setPoolName("e2e-hung-function-infra-pool");
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

    private String createFunction(String token, String functionKeyPrefix) throws Exception {
        String functionKey = functionKeyPrefix + "_" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/v1/functions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "functionKey": "%s",
                                  "name": "Hung Function Test",
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

    private void submitSource(String token, String functionId, String versionId, String source) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "files", "index.mjs", "text/javascript", source.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/functions/{functionId}/versions/{versionId}/source", functionId, versionId)
                        .file(file)
                        .param("entrypoint", "index.mjs")
                        .param("handler", "handler")
                        .header("Authorization", "Bearer " + token))
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

    private String createFlow(String token, String namePrefix) throws Exception {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        AppDomain domain = appDomainRepository.save(
                AppDomain.create(admin, "e2e-hung-" + namePrefix + "-" + UUID.randomUUID() + ".example.com", "verify-me", DomainStatus.VERIFIED));
        Gateway gateway = gatewayRepository.save(
                Gateway.create(admin, domain, "Hung Test Gateway " + namePrefix, "hng" + namePrefix.charAt(0) + System.nanoTime() % 100000, "test gateway", GatewayStatus.ACTIVE));

        String flowKey = "flw_hung_" + namePrefix + "_" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "flowKey": "%s",
                                  "name": "Hung Test Flow %s",
                                  "description": "created by HungFunctionDoesNotBlockOtherInvocationsE2ETest",
                                  "gatewayId": "%s",
                                  "httpMethod": "GET",
                                  "path": "/hung-%s-%s"
                                }
                                """.formatted(flowKey, namePrefix, gateway.getId(), namePrefix, flowKey)))
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
