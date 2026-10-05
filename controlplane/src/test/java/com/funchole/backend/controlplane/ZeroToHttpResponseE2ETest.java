package com.funchole.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.certificate.CertificateBundle;
import com.funchole.backend.certificate.CertificateProvider;
import com.funchole.backend.certificate.CertificateRequest;
import com.funchole.backend.certificate.GeneratedCertificate;
import com.funchole.backend.certificate.generator.SelfSignedCertificateGenerator;
import com.funchole.backend.certificate.store.CertificateLoader;
import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.entity.GatewayCertificate;
import com.funchole.backend.controlplane.repository.AppDomainRepository;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.GatewayCertificateRepository;
import com.funchole.backend.controlplane.repository.GatewayRepository;
import com.funchole.backend.dispatcher.ExecutionPlanner;
import com.funchole.backend.dispatcher.InvocationDispatcher;
import com.funchole.backend.dispatcher.IpcRuntimeExecutionGateway;
import com.funchole.backend.dispatcher.JdbcInvocationStepExecutionRegistry;
import com.funchole.backend.gateway.GatewayRegistry;
import com.funchole.backend.gateway.GatewayRegistryLoader;
import com.funchole.backend.gateway.GatewayRegistrySnapshot;
import com.funchole.backend.gateway.flow.SnapshotFlowResolver;
import com.funchole.backend.gateway.server.FixedHostProxy;
import com.funchole.backend.gateway.server.GatewayHealthChecker;
import com.funchole.backend.gateway.server.GatewayHttpHandler;
import com.funchole.backend.gateway.server.GatewayInvocationCompletionListener;
import com.funchole.backend.gateway.server.GatewayServer;
import com.funchole.backend.gateway.server.PendingInvocationResponseRegistry;
import com.funchole.backend.invocation.JdbcInvocationRegistry;
import com.funchole.backend.invocation.NatsJetStreamInvocationEventPublisher;
import com.funchole.backend.runtime.CachedArtifactStore;
import com.funchole.backend.runtime.FilesystemArtifactCache;
import com.funchole.backend.runtime.PersistentNodeExecutor;
import com.funchole.backend.runtime.RuntimeWorkerServer;
import com.funchole.backend.artifact.ArtifactStore;
import com.funchole.backend.artifact.S3ArtifactStore;
import com.funchole.backend.artifact.S3ArtifactStoreConfig;
import com.funchole.backend.runtimeregistry.InMemoryRuntimeRegistry;
import com.funchole.backend.runtimeregistry.RuntimeInstance;
import com.funchole.backend.runtimeregistry.RuntimeInstanceStatus;
import com.jayway.jsonpath.JsonPath;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.nats.client.Connection;
import io.nats.client.Nats;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import javax.sql.DataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
 * STORY-M1-10 - proves the entire platform lifecycle end-to-end, starting
 * from empty application data: authenticate, create a real Function, submit
 * real source, deploy to READY, create and adopt a real Flow with a
 * RESPONSE step, send a real HTTPS request to a real in-process Gateway,
 * have it actually dispatch through a real in-process Dispatcher to a real
 * in-process Runtime Worker (which spawns a real Node.js child process),
 * receive the function's own real response, and inspect the resulting
 * Invocation via the real GET /api/v1/invocations/{id} endpoint (STORY-M1-09).
 *
 * <p>Dispatcher/Runtime Worker/Gateway are real instances of their actual
 * production classes, wired in-process exactly the way DispatcherMain/
 * RuntimeWorkerMain/GatewayMain wire them - just inlined here instead of run
 * as separate OS processes, since nothing in this repo has ever combined
 * more than one of them in a single test before and separate processes has
 * no precedent and is far more fragile for CI. Postgres/NATS/MinIO (a real
 * S3-compatible endpoint, standing in for RustFS) are real via testcontainers.
 *
 * <p>Tagged {@code e2e} and excluded from the default {@code test} task (see
 * controlplane/build.gradle's {@code e2eTest} task) - this is far heavier
 * than every other test in the suite (multiple containers, a real Node
 * spawn, a real TLS handshake) and requires {@code node} on PATH and Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("e2e")
class ZeroToHttpResponseE2ETest {

    private static final String BUCKET_NAME = "funchole-e2e-artifacts";

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
    static void artifactProperties(DynamicPropertyRegistry registry) {
        registry.add("app.artifact.endpoint", minio::getS3URL);
        registry.add("app.artifact.bucket", () -> BUCKET_NAME);
        registry.add("app.artifact.access-key", minio::getUserName);
        registry.add("app.artifact.secret-key", minio::getPassword);
        registry.add("app.artifact.path-style-access", () -> true);
    }

    private static HikariDataSource infraDataSource;
    private static Connection natsConnection;
    private static PersistentNodeExecutor nodeExecutor;
    private static RuntimeWorkerServer runtimeWorkerServer;
    private static InvocationDispatcher dispatcher;
    private static IpcRuntimeExecutionGateway executionGateway;
    private static Thread dispatcherLoopThread;
    private static final AtomicBoolean dispatcherRunning = new AtomicBoolean(true);
    private static GatewayServer gatewayServer;
    private static GatewayHttpHandler gatewayHttpHandler;
    private static GatewayInvocationCompletionListener gatewayCompletionListener;
    private static GatewayRegistry gatewayRegistry;
    private static GatewayRegistryLoader gatewayRegistryLoader;
    private static final String RUNTIME_INSTANCE_ID = "runtime-node-e2e-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource controlplaneDataSource;

    @BeforeAll
    static void startRealStack() throws Exception {
        createBucket();

        infraDataSource = new HikariDataSource(hikariConfig());
        natsConnection = Nats.connect("nats://" + nats.getHost() + ":" + nats.getMappedPort(4222));

        Path socketPath = Path.of("/tmp/fh-e2e-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
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
        dispatcher = new InvocationDispatcher(
                natsConnection,
                new JdbcInvocationRegistry(infraDataSource, new NatsJetStreamInvocationEventPublisher(natsConnection)),
                new JdbcInvocationStepExecutionRegistry(infraDataSource),
                runtimeRegistry,
                new ExecutionPlanner(),
                executionGateway
        );
        dispatcherLoopThread = new Thread(() -> {
            while (dispatcherRunning.get()) {
                dispatcher.processNext(Duration.ofMillis(200));
            }
        }, "e2e-dispatcher-loop");
        dispatcherLoopThread.setDaemon(true);
        dispatcherLoopThread.start();
    }

    /**
     * Deliberately NOT part of {@link #startRealStack()}: {@code @BeforeAll}
     * runs before Spring's own context (and therefore Flyway) has
     * necessarily started, so querying real tables here would race Flyway.
     * Called explicitly from the test method instead, once the Spring
     * context is guaranteed up (proven by a real MockMvc call already having
     * succeeded).
     */
    private static void startGateway() throws Exception {
        CertificateLoader certificateLoader = reference -> certificateBundleHolder;
        gatewayRegistryLoader = new GatewayRegistryLoader(infraDataSource, certificateLoader);
        gatewayRegistry = new GatewayRegistry(gatewayRegistryLoader.load());
        JdbcInvocationRegistry gatewayInvocationRegistry =
                new JdbcInvocationRegistry(infraDataSource, new NatsJetStreamInvocationEventPublisher(natsConnection));
        PendingInvocationResponseRegistry pendingResponseRegistry = new PendingInvocationResponseRegistry(
                Executors.newSingleThreadScheduledExecutor(), Duration.ofSeconds(15));
        gatewayCompletionListener = new GatewayInvocationCompletionListener(natsConnection, pendingResponseRegistry);
        gatewayHttpHandler = new GatewayHttpHandler(
                new ObjectMapper(),
                gatewayRegistry,
                new SnapshotFlowResolver(gatewayRegistry),
                gatewayInvocationRegistry,
                pendingResponseRegistry,
                Executors.newFixedThreadPool(2),
                null,
                new GatewayHealthChecker(infraDataSource, natsConnection),
                FixedHostProxy.empty()
        );
        gatewayServer = new GatewayServer(0, gatewayRegistry, gatewayHttpHandler);
        gatewayServer.start();
    }

    @AfterAll
    static void stopRealStack() {
        dispatcherRunning.set(false);
        if (dispatcherLoopThread != null) {
            dispatcherLoopThread.interrupt();
        }
        if (gatewayServer != null) {
            gatewayServer.close();
        }
        if (gatewayCompletionListener != null) {
            gatewayCompletionListener.close();
        }
        if (gatewayHttpHandler != null) {
            gatewayHttpHandler.close();
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
    void zeroToHttpResponseEndToEnd() throws Exception {
        String token = obtainToken();
        startGateway();

        String functionId = createFunction(token);
        String versionId = createDraftFunctionVersion(token, functionId);
        submitSource(token, functionId, versionId);
        deployAndAssertReady(token, functionId, versionId);

        String hostname = createGateway();

        String flowKey = "flw_e2e_" + UUID.randomUUID().toString().replace("-", "");
        String path = "/e2e-" + UUID.randomUUID().toString().replace("-", "");
        String flowId = createFlow(token, flowKey, path);
        String flowVersionId = createDraftFlowVersion(token, flowId);
        addResponseStep(token, flowId, flowVersionId, functionId, versionId);
        adoptFlowVersion(token, flowId, flowVersionId);

        gatewayRegistry.replace(gatewayRegistryLoader.load());

        HttpResponse response = sendRealHttpsRequest(gatewayServer.boundPort(), hostname, path);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"ok\":true").contains("\"path\":\"" + path + "\"");

        UUID invocationId = findLatestInvocationId(UUID.fromString(flowId));
        assertThat(invocationId).isNotNull();

        String inspectionBody = mockMvc.perform(get("/api/v1/invocations/{invocationId}", invocationId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.steps.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        // The step's own raw result is the RESPONSE function's unwrapped
        // {status, body} return value (Postgres's jsonb-to-text cast adds a
        // space after each colon/comma, unlike the compact JSON the Gateway
        // itself writes to the actual HTTP response asserted above).
        String stepResult = JsonPath.read(inspectionBody, "$.data.steps[0].result");
        assertThat(stepResult).contains("\"ok\": true").contains("\"path\": \"" + path + "\"");
    }

    private record HttpResponse(int statusCode, String body) {
    }

    private HttpResponse sendRealHttpsRequest(int port, String hostname, String path) throws Exception {
        SSLContext sslContext = trustAllSslContext();
        SSLSocketFactory factory = sslContext.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket()) {
            socket.connect(new InetSocketAddress("localhost", port), 5000);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName(hostname)));
            socket.setSSLParameters(parameters);
            socket.startHandshake();

            OutputStream out = socket.getOutputStream();
            String request = "GET " + path + " HTTP/1.1\r\nHost: " + hostname + "\r\nConnection: close\r\n\r\n";
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return parseHttpResponse(lines);
        }
    }

    private HttpResponse parseHttpResponse(List<String> lines) {
        int statusCode = Integer.parseInt(lines.get(0).split(" ")[1]);
        int blankLineIndex = lines.indexOf("");
        String body = blankLineIndex >= 0 && blankLineIndex + 1 < lines.size()
                ? String.join("", lines.subList(blankLineIndex + 1, lines.size()))
                : "";
        return new HttpResponse(statusCode, body);
    }

    private static SSLContext trustAllSslContext() throws Exception {
        TrustManager trustAll = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
        return context;
    }

    private UUID findLatestInvocationId(UUID flowId) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(controlplaneDataSource);
        return jdbcTemplate.queryForObject(
                "select id from invocations where flow_id = ? order by created_at desc limit 1",
                UUID.class,
                flowId
        );
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
        config.setPoolName("e2e-infra-db-pool");
        return config;
    }

    /**
     * GatewayHttpHandler's own "unknown-host" gate (separate from routing
     * and separate from TLS SNI) requires a real, ACTIVE {@code certificates}
     * row joined to the Gateway before {@code gatewayRegistry.entries()} is
     * non-empty - a Gateway with no certificate at all is invisible to it,
     * confirmed by a first attempt at this test 404ing with exactly that
     * reason. Controlplane's own certificate provisioning is event/OpenBao
     * driven (GatewayCertificateService/OpenBaoCertificateStore) - out of
     * scope for a hermetic test - so this generates a real self-signed
     * bundle directly via the certificate module's own generator and saves
     * the certificate row by hand, matching what that pipeline would
     * eventually produce. The test's own {@code CertificateLoader} stub
     * (see {@link #startGateway()}) returns this same bundle regardless of
     * the secret_ref passed in - there is no OpenBao here to resolve it from.
     */
    private String createGateway() {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        String domainName = "e2e-test-" + UUID.randomUUID() + ".example.com";
        AppDomain domain = appDomainRepository.save(AppDomain.create(admin, domainName, "verify-me", DomainStatus.VERIFIED));
        String uniqueKey = "e2e" + (System.nanoTime() % 100000);
        Gateway gateway = gatewayRepository.save(Gateway.create(
                admin, domain, "E2E Test Gateway", uniqueKey, "created by ZeroToHttpResponseE2ETest", GatewayStatus.ACTIVE));
        gatewayIdHolder = gateway.getId();
        String hostname = (uniqueKey + "." + domainName).toLowerCase();

        GeneratedCertificate generated = new SelfSignedCertificateGenerator(Duration.ofDays(30))
                .generate(new CertificateRequest(hostname, List.of(hostname)));
        certificateBundleHolder = generated.bundle();
        GatewayCertificate certificate = GatewayCertificate.create(
                gateway, hostname, null, CertificateProvider.SELF_SIGNED, "e2e-test-secret-ref");
        certificate.markActive(generated.issuedAt(), generated.expiresAt());
        gatewayCertificateRepository.save(certificate);

        return hostname;
    }

    private UUID gatewayIdHolder;
    private static CertificateBundle certificateBundleHolder;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppDomainRepository appDomainRepository;

    @Autowired
    private GatewayRepository gatewayRepository;

    @Autowired
    private GatewayCertificateRepository gatewayCertificateRepository;

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
        String functionKey = "fn_e2e_" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/v1/functions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "functionKey": "%s",
                                  "name": "E2E Test Function",
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
                        .content("{\"runtime\":\"NODE\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
    }

    private void submitSource(String token, String functionId, String versionId) throws Exception {
        String source = "export async function handler(input) { return { status: 200, body: { ok: true, path: input.path } }; }";
        MockMultipartFile file = new MockMultipartFile("files", "index.mjs", "text/javascript", source.getBytes(StandardCharsets.UTF_8));
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

        // Deploy is asynchronous by design (PUBLISHING -> build -> READY, can
        // take minutes per FunctionVersionDeploymentService's own docs) -
        // poll rather than assume it finished within this one request/response.
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

    private String createFlow(String token, String flowKey, String path) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "flowKey": "%s",
                                  "name": "E2E Test Flow",
                                  "description": "created by ZeroToHttpResponseE2ETest",
                                  "gatewayId": "%s",
                                  "httpMethod": "GET",
                                  "path": "%s"
                                }
                                """.formatted(flowKey, gatewayIdHolder, path)))
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

    private void adoptFlowVersion(String token, String flowId, String flowVersionId) throws Exception {
        mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/adopt", flowId, flowVersionId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ADOPTED"));
    }
}
