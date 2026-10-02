package com.funchole.backend.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.funchole.backend.controlplane.config.CorsProperties;
import com.funchole.backend.controlplane.config.McpGuidanceConfig;
import com.funchole.backend.controlplane.config.SecurityConfig;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.security.ApiKeyAuthenticationFilter;
import com.funchole.backend.controlplane.security.JwtAuthenticationFilter;
import com.funchole.backend.controlplane.security.JwtService;
import com.funchole.backend.controlplane.service.ApiKeyService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.stream.IntStream;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetailsService;

/** Real HTTP and the production security chain, without Postgres/Docker or paid API calls. */
@SpringBootTest(classes = McpCompatibilityHttpTests.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
                "spring.ai.mcp.server.capabilities.logging=false",
                "management.endpoints.web.exposure.include=health"
        })
class McpCompatibilityHttpTests {
    private static final AppUser ALICE = AppUser.createFromGoogleSignUp("alice", "alice@example.test", "Alice", "unusable");
    private static final AppUser BOB = AppUser.createFromGoogleSignUp("bob", "bob@example.test", "Bob", "unusable");
    private static final AppUser CAROL = AppUser.createFromGoogleSignUp("carol", "carol@example.test", "Carol", "unusable");
    private static final String ALICE_KEY = "fh_mcp_alice_fixture";
    private static final String BOB_KEY = "fh_mcp_bob_fixture";
    private static final String CAROL_KEY = "fh_mcp_carol_fixture";
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() { };
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired McpGuideCatalog guides;
    @Autowired McpToolRateLimiter rateLimiter;

    @TestComponent
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({SecurityConfig.class, ApiKeyAuthenticationFilter.class, JwtAuthenticationFilter.class,
            McpGuidanceConfig.class, McpGuideCatalog.class, McpToolCatalog.class, McpDiscoveryTools.class,
            ApplicationGuidance.class, FunctionExampleMcpTools.class, ModernMcpProtocol.class, McpProtocolFilter.class, McpToolRateLimiter.class,
            IdentityTool.class})
    static class Fixture {
        @Bean CorsProperties corsProperties() { return new CorsProperties(List.of("https://allowed.example")); }
        @Bean ApiKeyService apiKeyService() {
            var service = mock(ApiKeyService.class);
            when(service.resolve(ALICE_KEY)).thenReturn(Optional.of(ALICE));
            when(service.resolve(BOB_KEY)).thenReturn(Optional.of(BOB));
            when(service.resolve(CAROL_KEY)).thenReturn(Optional.of(CAROL));
            return service;
        }
        @Bean JwtService jwtService() { return mock(JwtService.class); }
        @Bean UserDetailsService userDetailsService() { return mock(UserDetailsService.class); }
        @Bean List<SyncToolSpecification> paginationFixtureTools() {
            return IntStream.range(0, 25).mapToObj(i -> SyncToolSpecification.builder()
                    .tool(McpSchema.Tool.builder("fixture_page_" + i, Map.of("type", "object")).description("Pagination fixture").build())
                    .callHandler((exchange, request) -> McpSchema.CallToolResult.builder()
                            .content(List.of(McpSchema.TextContent.builder("ok").build())).build()).build()).toList();
        }
    }

    static class IdentityTool {
        @McpTool(name = "fixture_identity", description = "Identify the current authenticated user and echo typed input.", generateOutputSchema = true)
        public Map<String, Object> identity(@McpToolParam(description = "Required message") String message) {
            return Map.of("userId", CurrentMcpUser.id().toString(), "message", message);
        }

        @McpTool(name = "fixture_failure", description = "Test an internal callback exception.")
        public String failure() {
            throw new IllegalStateException("Internal database password=fixture-secret-do-not-leak");
        }
    }

    @Test
    void modernDiscoveryHasNoInitializationOrSessionAndListsSupportedVersions() throws Exception {
        var response = modern("server/discover", Map.of(), ALICE_KEY);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Mcp-Session-Id")).isEmpty();
        var result = result(response);
        assertThat(result.get("supportedVersions")).isEqualTo(ModernMcpProtocol.VERSIONS);
        assertThat(result.get("resultType")).isEqualTo("complete");
        assertThat(result.get("ttlMs")).isEqualTo(300000);
        assertThat(result.get("cacheScope")).isEqualTo("private");
        assertThat(result.get("instructions").toString()).hasSizeLessThanOrEqualTo(1200);
    }

    @Test
    void modernToolsResourcesAndPromptsShareNativeContracts() throws Exception {
        var tools = result(modern("tools/list", Map.of(), ALICE_KEY));
        String allTools = tools.get("tools").toString();
        while (tools.containsKey("nextCursor")) {
            tools = result(modern("tools/list", Map.of("cursor", tools.get("nextCursor")), ALICE_KEY));
            allTools += tools.get("tools").toString();
        }
        assertThat(allTools).contains("get_funchole_guide", "search_funchole", "plan_application");
        var resource = result(modern("resources/read", Map.of("uri", "funchole://guides/static"), ALICE_KEY));
        assertThat(resource.get("contents").toString()).contains(guides.read("static"));
        var fallback = result(modern("tools/call", Map.of("name", "get_funchole_guide", "arguments", Map.of("topic", "static")), ALICE_KEY));
        assertThat(fallback.get("content").toString()).contains(guides.read("static"));
        assertThat(result(modern("prompts/list", Map.of(), ALICE_KEY)).get("prompts").toString()).contains("build_application", "repair_application");
        var prompt = result(modern("prompts/get", Map.of("name", "build_application", "arguments", Map.of("idea", "A blog")), ALICE_KEY));
        assertThat(prompt.get("messages").toString()).contains("A blog", "funchole://guides/start");
        assertThat(result(modern("resources/templates/list", Map.of(), ALICE_KEY)).get("resourceTemplates")).isEqualTo(List.of());
        assertThat(result(modern("ping", Map.of(), ALICE_KEY)).get("resultType")).isEqualTo("complete");
    }

    @Test
    void modernCallsResolveIdentityPerRequestInsteadOfCachingATenant() throws Exception {
        var args = Map.of("name", "fixture_identity", "arguments", Map.of("message", "hello"));
        var alice = result(modern("tools/call", args, ALICE_KEY));
        var bob = result(modern("tools/call", args, BOB_KEY));
        assertThat(alice.get("structuredContent").toString()).contains(ALICE.getId().toString()).doesNotContain(BOB.getId().toString());
        assertThat(bob.get("structuredContent").toString()).contains(BOB.getId().toString()).doesNotContain(ALICE.getId().toString());
        var invalid = result(modern("tools/call", Map.of("name", "fixture_identity", "arguments", Map.of("message", 42)), ALICE_KEY));
        assertThat(invalid.get("isError")).isEqualTo(true);
        var failure = modern("tools/call", Map.of("name", "fixture_failure"), ALICE_KEY);
        assertThat(result(failure).get("isError")).isEqualTo(true);
        assertThat(failure.body()).contains("troubleshooting").doesNotContain("fixture-secret-do-not-leak", "IllegalStateException");
    }

    @Test
    void exhaustedUserBudgetReturnsRetryAfterWithoutBlockingOtherUsers() throws Exception {
        for (int i = 0; i < 120; i++) assertThat(rateLimiter.tryAcquire(CAROL.getId())).isTrue();
        var params = Map.<String, Object>of("name", "get_funchole_guide", "arguments", Map.of("topic", "start"));
        var limited = modern("tools/call", params, CAROL_KEY);
        assertError(limited, 429, 1001);
        assertThat(limited.headers().firstValue("Retry-After")).contains("60");
        assertThat(modern("tools/call", params, BOB_KEY).statusCode()).isEqualTo(200);
        assertThat(modern("resources/list", Map.of(), CAROL_KEY).statusCode()).isEqualTo(200);
    }

    @Test
    void modernRejectsUnknownMethodsVersionsAndBadParams() throws Exception {
        assertError(modern("unknown/method", Map.of(), ALICE_KEY), 404, -32601);
        assertError(modern("resources/read", Map.of("uri", "funchole://guides/missing"), ALICE_KEY), 400, -32602);
        assertError(modern("tools/call", Map.of("name", "invented", "arguments", Map.of()), ALICE_KEY), 400, -32602);
        assertError(modern("prompts/get", Map.of("name", "build_application"), ALICE_KEY), 400, -32602);
        assertError(modern("prompts/get", Map.of("name", "repair_application", "arguments", Map.of("symptom", 42)), ALICE_KEY), 400, -32602);
        assertError(modern("tools/list", Map.of("cursor", "bad"), ALICE_KEY), 400, -32602);
        assertError(modern("tools/list", Map.of("cursor", "-1"), ALICE_KEY), 400, -32602);
        var missing = modernMessage("ping", Map.of());
        var missingParams = map(missing.get("params"));
        var missingMeta = map(missingParams.get("_meta"));
        missingMeta.remove(ModernMcpProtocol.CAPABILITIES_META);
        missingParams.put("_meta", missingMeta);
        missing.put("params", missingParams);
        assertError(post(missing, modernHeaders("ping", null), ALICE_KEY), 400, -32602);
        var future = modernMessage("ping", Map.of());
        var futureParams = map(future.get("params"));
        var futureMeta = map(futureParams.get("_meta"));
        futureMeta.put(ModernMcpProtocol.VERSION_META, "2099-01-01");
        futureParams.put("_meta", futureMeta);
        future.put("params", futureParams);
        var headers = new LinkedHashMap<>(modernHeaders("ping", null));
        headers.put("MCP-Protocol-Version", "2099-01-01");
        assertError(post(future, headers, ALICE_KEY), 400, -32022);
        var invalidId = modernMessage("ping", Map.of());
        invalidId.put("id", null);
        assertError(post(invalidId, modernHeaders("ping", null), ALICE_KEY), 400, -32600);
    }

    @Test
    void modernValidatesMirroredHeadersBeforeDispatch() throws Exception {
        var message = modernMessage("tools/call", Map.of("name", "get_funchole_guide", "arguments", Map.of("topic", "start")));
        var headers = new LinkedHashMap<>(modernHeaders("tools/call", "get_funchole_guide"));
        headers.put("Mcp-Name", "wrong");
        assertError(post(message, headers, ALICE_KEY), 400, -32020);
        var missingVersion = new LinkedHashMap<>(modernHeaders("tools/call", "get_funchole_guide"));
        missingVersion.remove("MCP-Protocol-Version");
        assertError(post(message, missingVersion, ALICE_KEY), 400, -32020);
        headers.remove("Mcp-Name");
        assertError(post(message, headers, ALICE_KEY), 400, -32020);
        headers.put("Mcp-Name", "=?base64?Z2V0X2Z1bmNob2xlX2d1aWRl?=");
        assertThat(post(message, headers, ALICE_KEY).statusCode()).isEqualTo(200);
        headers.put("Mcp-Method", "ping");
        assertError(post(message, headers, ALICE_KEY), 400, -32020);
    }

    @Test
    void transportRejectsMalformedOversizedAndUnsupportedHttpRequests() throws Exception {
        var headers = modernHeaders("ping", null);
        var builder = HttpRequest.newBuilder(endpoint()).header("Authorization", "Bearer " + ALICE_KEY)
                .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json");
        headers.forEach(builder::header);
        var malformed = http.send(builder.POST(HttpRequest.BodyPublishers.ofString("{" )).build(), HttpResponse.BodyHandlers.ofString());
        assertError(malformed, 400, -32700);
        var batch = http.send(builder.POST(HttpRequest.BodyPublishers.ofString("[]")).build(), HttpResponse.BodyHandlers.ofString());
        assertError(batch, 400, -32600);
        var oversized = http.send(builder.POST(HttpRequest.BodyPublishers.ofString("x".repeat(8 * 1024 * 1024 + 1))).build(), HttpResponse.BodyHandlers.ofString());
        assertError(oversized, 413, -32600);
        var duplicate = HttpRequest.newBuilder(endpoint()).header("Authorization", "Bearer " + ALICE_KEY)
                .header("Accept", "application/json, text/event-stream").header("Content-Type", "application/json")
                .header("MCP-Protocol-Version", ModernMcpProtocol.VERSION).header("Mcp-Method", "ping").header("Mcp-Method", "tools/list")
                .POST(HttpRequest.BodyPublishers.ofString(McpJsonDefaults.getMapper().writeValueAsString(modernMessage("ping", Map.of()))));
        assertError(http.send(duplicate.build(), HttpResponse.BodyHandlers.ofString()), 400, -32020);
        var wrongAccept = HttpRequest.newBuilder(endpoint()).header("Authorization", "Bearer " + ALICE_KEY)
                .header("Content-Type", "application/json").header("Accept", "application/json");
        headers.forEach(wrongAccept::header);
        assertError(http.send(wrongAccept.POST(HttpRequest.BodyPublishers.ofString(McpJsonDefaults.getMapper().writeValueAsString(modernMessage("ping", Map.of())))).build(),
                HttpResponse.BodyHandlers.ofString()), 406, -32600);
        var get = http.send(HttpRequest.newBuilder(endpoint()).header("Authorization", "Bearer " + ALICE_KEY)
                .header("MCP-Protocol-Version", ModernMcpProtocol.VERSION).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(405);
        assertThat(get.headers().firstValue("Allow")).contains("POST");
    }

    @Test
    void authenticationOriginAndCorsRemainAheadOfModernExecution() throws Exception {
        var message = modernMessage("server/discover", Map.of());
        assertThat(post(message, modernHeaders("server/discover", null), null).statusCode()).isEqualTo(401);
        assertThat(post(message, modernHeaders("server/discover", null), "fh_mcp_invalid").statusCode()).isEqualTo(401);
        var headers = new LinkedHashMap<>(modernHeaders("server/discover", null));
        headers.put("Origin", "https://evil.example");
        assertThat(post(message, headers, ALICE_KEY).statusCode()).isEqualTo(403);
        headers.put("Origin", "https://allowed.example");
        var allowed = post(message, headers, ALICE_KEY);
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("https://allowed.example");
        var preflight = http.send(HttpRequest.newBuilder(endpoint()).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://allowed.example").header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,mcp-protocol-version,mcp-method,mcp-name")
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(preflight.statusCode()).isEqualTo(200);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase()).contains("mcp-method", "mcp-name");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FUNCHOLE_TEST_MCP_SMOKE", matches = "true")
    void smokeScriptWorksAgainstTheRealHttpFixture() throws Exception {
        Path script = Path.of("../scripts/dev/mcp-smoke.py").toAbsolutePath().normalize();
        var builder = new ProcessBuilder("python3", script.toString(), "--url", endpoint().toString()).redirectErrorStream(true);
        builder.environment().put("FUNCHOLE_MCP_API_KEY", ALICE_KEY);
        var process = builder.start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("Smoke script finishes within 60 seconds").isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.exitValue()).as(output).isZero();
            ModernMcpProtocol.VERSIONS.forEach(version -> assertThat(output).contains("PASS " + version));
            assertThat(output).doesNotContain(ALICE_KEY);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025-11-25", "2025-06-18", "2025-03-26"})
    void legacyInitializationSessionAndToolResourcePromptOperationsStillWork(String version) throws Exception {
        var initialized = post(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of(
                "protocolVersion", version, "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1"))), Map.of(), ALICE_KEY);
        assertThat(initialized.statusCode()).isEqualTo(200);
        assertThat(result(initialized).get("protocolVersion")).isEqualTo(version);
        String session = initialized.headers().firstValue("Mcp-Session-Id").orElseThrow();
        Map<String, String> headers = Map.of("MCP-Protocol-Version", version, "Mcp-Session-Id", session);
        var notify = post(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"), headers, ALICE_KEY);
        assertThat(notify.statusCode()).isEqualTo(202);
        var toolList = legacy("tools/list", Map.of(), headers);
        assertThat(toolList.get("tools").toString()).contains("fixture_identity", "get_funchole_guide");
        assertThat(toolList).doesNotContainKey("resultType");
        assertThat(legacy("tools/call", Map.of("name", "fixture_identity", "arguments", Map.of("message", "legacy")), headers)
                .get("structuredContent").toString()).contains(ALICE.getId().toString());
        var bob = post(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call", "params", Map.of(
                "name", "fixture_identity", "arguments", Map.of("message", "bob"))), headers, BOB_KEY);
        assertThat(result(bob).get("structuredContent").toString()).contains(BOB.getId().toString()).doesNotContain(ALICE.getId().toString());
        assertThat(legacy("resources/list", Map.of(), headers).get("resources").toString()).contains("funchole://guides/start");
        assertThat(legacy("resources/read", Map.of("uri", "funchole://guides/start"), headers).get("contents").toString()).contains(guides.read("start"));
        assertThat(legacy("prompts/list", Map.of(), headers).get("prompts").toString()).contains("build_application");
        assertThat(legacy("prompts/get", Map.of("name", "build_application", "arguments", Map.of("idea", "Legacy blog")), headers)
                .get("messages").toString()).contains("Legacy blog");
    }

    private Map<String, Object> legacy(String method, Map<String, Object> params, Map<String, String> headers) throws Exception {
        var response = post(Map.of("jsonrpc", "2.0", "id", 2, "method", method, "params", params), headers, ALICE_KEY);
        assertThat(response.statusCode()).isEqualTo(200);
        return result(response);
    }

    private HttpResponse<String> modern(String method, Map<String, Object> params, String token) throws Exception {
        String name = (String) params.get(method.equals("resources/read") ? "uri" : "name");
        return post(modernMessage(method, params), modernHeaders(method, name), token);
    }

    private static Map<String, Object> modernMessage(String method, Map<String, Object> params) {
        var copy = new LinkedHashMap<>(params);
        var meta = new LinkedHashMap<String, Object>();
        meta.put(ModernMcpProtocol.VERSION_META, ModernMcpProtocol.VERSION);
        meta.put(ModernMcpProtocol.CAPABILITIES_META, Map.of());
        copy.put("_meta", meta);
        return new LinkedHashMap<>(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", copy));
    }

    private static Map<String, String> modernHeaders(String method, String name) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("MCP-Protocol-Version", ModernMcpProtocol.VERSION);
        headers.put("Mcp-Method", method);
        if (name != null) headers.put("Mcp-Name", name);
        return headers;
    }

    private HttpResponse<String> post(Map<String, Object> message, Map<String, String> headers, String token) throws Exception {
        var builder = HttpRequest.newBuilder(endpoint()).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(McpJsonDefaults.getMapper().writeValueAsString(message)));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        headers.forEach(builder::header);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI endpoint() { return URI.create("http://localhost:" + port + "/api/mcp"); }

    private static Map<String, Object> result(HttpResponse<String> response) throws Exception {
        String body = response.body();
        if (response.headers().firstValue("Content-Type").orElse("").contains("text/event-stream")) {
            body = body.lines().filter(line -> line.startsWith("data:")).map(line -> line.substring(5).trim())
                    .reduce((first, second) -> second).orElseThrow();
        }
        var parsed = McpJsonDefaults.getMapper().readValue(body, MAP_TYPE);
        assertThat(parsed).containsKey("result");
        return map(parsed.get("result"));
    }

    private static Map<String, Object> map(Object value) { return ModernMcpProtocol.object(value, "test result"); }

    private static void assertError(HttpResponse<String> response, int status, int code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var parsed = McpJsonDefaults.getMapper().readValue(response.body(), MAP_TYPE);
        assertThat(map(parsed.get("error")).get("code")).isEqualTo(code);
    }
}
