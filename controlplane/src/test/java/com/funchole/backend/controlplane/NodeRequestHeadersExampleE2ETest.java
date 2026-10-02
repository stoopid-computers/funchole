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
import com.funchole.backend.controlplane.mcp.FunctionExampleFixtures;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import java.nio.file.Path;

/**
 * Proves {@link FunctionExampleFixtures#NODE_REQUEST_HEADERS_SOURCE} - the exact content
 * {@code get_function_example} returns for the NODE_REQUEST_HEADERS scenario - is a real, working
 * example, through a real HTTP request over a real in-process Gateway (not the direct-invoke REST
 * path other example E2E tests use, since headers/cookies only exist on this path): sends a real
 * HTTPS request carrying a custom header and a Cookie header, and asserts the response reflects
 * both, and carries {@code X-Response-Header} plus two separate {@code Set-Cookie} lines (not one
 * comma-joined header, which would silently break every cookie after the first).
 *
 * <p>Reuses {@link ZeroToHttpResponseE2ETest}'s in-process real-Gateway architecture directly.
 *
 * <p>Tagged {@code e2e}, same reasons as {@link ZeroToHttpResponseE2ETest}: requires {@code node}
 * on PATH and Docker, heavier than the default suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@Tag("e2e")
class NodeRequestHeadersExampleE2ETest {

    private static final String BUCKET_NAME = "funchole-e2e-request-headers-example";
    private static final String RUNTIME_INSTANCE_ID = "runtime-node-request-headers-example-1";

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppDomainRepository appDomainRepository;

    @Autowired
    private GatewayRepository gatewayRepository;

    @Autowired
    private GatewayCertificateRepository gatewayCertificateRepository;

    private UUID gatewayIdHolder;
    private static CertificateBundle certificateBundleHolder;

    @BeforeAll
    static void startRealStack() throws Exception {
        createBucket();

        infraDataSource = new HikariDataSource(hikariConfig());
        natsConnection = Nats.connect("nats://" + nats.getHost() + ":" + nats.getMappedPort(4222));

        Path socketPath = Path.of("/tmp/fh-e2e-headers-" + UUID.randomUUID().toString().substring(0, 8) + ".sock");
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
        }, "e2e-headers-dispatcher-loop");
        dispatcherLoopThread.setDaemon(true);
        dispatcherLoopThread.start();
    }

    /** See {@link ZeroToHttpResponseE2ETest#startGateway()} for why this isn't in {@code @BeforeAll}. */
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
    void nodeRequestHeadersExampleReadsRequestHeadersAndCookiesAndSetsResponseOnes() throws Exception {
        String token = obtainToken();
        startGateway();

        String functionId = createFunction(token);
        String versionId = createDraftFunctionVersion(token, functionId);
        submitSource(token, functionId, versionId);
        deployAndAssertReady(token, functionId, versionId);

        String hostname = createGateway();

        String flowKey = "flw_e2e_headers_" + UUID.randomUUID().toString().replace("-", "");
        String path = "/e2e-headers-" + UUID.randomUUID().toString().replace("-", "");
        String flowId = createFlow(token, flowKey, path);
        String flowVersionId = createDraftFlowVersion(token, flowId);
        addResponseStep(token, flowId, flowVersionId, functionId, versionId);
        adoptFlowVersion(token, flowId, flowVersionId);

        gatewayRegistry.replace(gatewayRegistryLoader.load());

        HttpResponse response = sendRealHttpsRequest(gatewayServer.boundPort(), hostname, path);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"customHeader\":\"abc123\"").contains("\"sessionCookie\":\"xyz\"");
        assertThat(response.headerValues("X-Response-Header")).containsExactly("from-function");
        assertThat(response.headerValues("Set-Cookie"))
                .containsExactlyInAnyOrder("greeted=true; Path=/", "visits=1; Path=/");
    }

    private record HttpResponse(int statusCode, List<String> headerLines, String body) {
        List<String> headerValues(String name) {
            String prefix = name + ":";
            return headerLines.stream()
                    .filter(line -> line.regionMatches(true, 0, prefix, 0, prefix.length()))
                    .map(line -> line.substring(prefix.length()).trim())
                    .toList();
        }
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
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostname + "\r\n"
                    + "X-Custom-Header: abc123\r\n"
                    + "Cookie: session=xyz; theme=dark\r\n"
                    + "Connection: close\r\n\r\n";
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
        List<String> headerLines = blankLineIndex >= 0 ? lines.subList(1, blankLineIndex) : List.of();
        String body = blankLineIndex >= 0 && blankLineIndex + 1 < lines.size()
                ? String.join("", lines.subList(blankLineIndex + 1, lines.size()))
                : "";
        return new HttpResponse(statusCode, headerLines, body);
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
        config.setPoolName("e2e-headers-infra-db-pool");
        return config;
    }

    /** See {@link ZeroToHttpResponseE2ETest#createGateway()} for why this is built this way. */
    private String createGateway() {
        AppUser admin = appUserRepository.findByUsername("admin").orElseThrow();
        String domainName = "e2e-headers-test-" + UUID.randomUUID() + ".example.com";
        AppDomain domain = appDomainRepository.save(AppDomain.create(admin, domainName, "verify-me", DomainStatus.VERIFIED));
        String uniqueKey = "e2eh" + (System.nanoTime() % 100000);
        Gateway gateway = gatewayRepository.save(Gateway.create(
                admin, domain, "E2E Headers Test Gateway", uniqueKey, "created by NodeRequestHeadersExampleE2ETest", GatewayStatus.ACTIVE));
        gatewayIdHolder = gateway.getId();
        String hostname = (uniqueKey + "." + domainName).toLowerCase();

        GeneratedCertificate generated = new SelfSignedCertificateGenerator(Duration.ofDays(30))
                .generate(new CertificateRequest(hostname, List.of(hostname)));
        certificateBundleHolder = generated.bundle();
        GatewayCertificate certificate = GatewayCertificate.create(
                gateway, hostname, null, CertificateProvider.SELF_SIGNED, "e2e-headers-test-secret-ref");
        certificate.markActive(generated.issuedAt(), generated.expiresAt());
        gatewayCertificateRepository.save(certificate);

        return hostname;
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
        String functionKey = "fn_e2e_headers_" + UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/v1/functions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "functionKey": "%s",
                                  "name": "E2E Headers Test Function",
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
                "files", FunctionExampleFixtures.NODE_REQUEST_HEADERS_ENTRYPOINT, "text/javascript",
                FunctionExampleFixtures.NODE_REQUEST_HEADERS_SOURCE.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/functions/{functionId}/versions/{versionId}/source", functionId, versionId)
                        .file(file)
                        .param("entrypoint", FunctionExampleFixtures.NODE_REQUEST_HEADERS_ENTRYPOINT)
                        .param("handler", FunctionExampleFixtures.NODE_REQUEST_HEADERS_HANDLER)
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

    private String createFlow(String token, String flowKey, String path) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "flowKey": "%s",
                                  "name": "E2E Headers Test Flow",
                                  "description": "created by NodeRequestHeadersExampleE2ETest",
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
