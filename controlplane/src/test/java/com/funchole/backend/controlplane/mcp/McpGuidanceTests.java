package com.funchole.backend.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.Map;
import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.dto.FlowVersionResponse;
import com.funchole.backend.controlplane.dto.FlowFullSourceResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionResponse;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

class McpGuidanceTests {
    @TestConfiguration(proxyBeanMethods = false)
    @Import({McpGuideCatalog.class, McpToolCatalog.class, McpResourceCatalog.class, ApplicationGuidance.class,
            com.funchole.backend.controlplane.config.McpGuidanceConfig.class, McpTestConfiguration.class})
    static class Fixture {
    }

    @Test
    void everyGuideHasTheSameBodyThroughResourceAndToolAndAllLinksResolve() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var guides = context.getBean(McpGuideCatalog.class);
            var discovery = context.getBean(McpReadTools.class);
            var references = Pattern.compile("funchole://guides/([a-z]+)");
            for (var guide : guides.guides()) {
                assertThat(guide.description()).hasSizeLessThanOrEqualTo(240);
                String body = guides.read(guide.topic());
                assertThat(body).startsWith("# ");
                assertThat(discovery.read(guide.uri(), null, null, null, null).data()).isEqualTo(body);
                var resource = context.getBean(McpResourceCatalog.class).read(guide.uri()).contents().get(0);
                assertThat(resource).isInstanceOfSatisfying(io.modelcontextprotocol.spec.McpSchema.TextResourceContents.class,
                        content -> assertThat(content.text()).isEqualTo(body));
                references.matcher(body).results().forEach(match -> assertThat(guides.read(match.group(1))).isNotBlank());
            }
            assertThatThrownBy(() -> guides.read("../../application.yml")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> guides.read("funchole://guides/missing")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void searchIsBoundedPaginatedAndUsesActualSchemas() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var discovery = context.getBean(McpReadTools.class);
            var first = (McpReadTools.Page) discovery.discover(null, "", null, 0, 2).data();
            assertThat(first.items()).hasSize(2);
            assertThat(first.total()).isGreaterThan(2);
            assertThat(first.nextOffset()).isEqualTo(2);
            var next = (McpReadTools.Page) discovery.discover(null, null, null, first.nextOffset(), 2).data();
            assertThat(next.items().stream().noneMatch(first.items()::contains)).isTrue();
            assertThat(((McpReadTools.Page) discovery.discover(null, "nothingmatchesxyz", null, null, null).data()).items()).isEmpty();
            assertThat(((McpReadTools.Page) discovery.discover(null, null, null, Integer.MAX_VALUE, 20).data()).items()).isEmpty();
            assertThat(discovery.knowledge()).anyMatch(m -> m.pointer().equals("funchole://guides/static"));
            var schema = (io.modelcontextprotocol.spec.McpSchema.Tool) discovery.read("funchole://tools/build_function", null, null, null, null).data();
            assertThat(schema.inputSchema()).containsKey("properties");
            assertThat(discovery.discover(null, null, null, -1, 2).ok()).isFalse();
            assertThat(discovery.discover(null, null, null, 0, 21).ok()).isFalse();
            assertThat(discovery.discover(null, "x".repeat(301), null, 0, 2).ok()).isFalse();
            assertThat(discovery.read("funchole://tools/invented_tool", null, null, null, null).ok()).isFalse();
            assertThat(discovery.knowledge()).allSatisfy(m -> assertThat(m.description()).hasSizeLessThanOrEqualTo(240));
        }
    }

    @Test
    void plansAndPromptsNameShippingChecksAndDoNotClaimMissingCapabilities() {
        var guidance = new ApplicationGuidance();
        var guides = new McpGuideCatalog();
        for (String kind : List.of("STATIC", "dynamic", "MULTIPLAYER")) {
            var plan = guidance.plan(kind);
            plan.guideUris().forEach(uri -> assertThat(guides.read(uri)).isNotBlank());
            assertThat(plan.steps()).anyMatch(step -> step.contains("READY"));
            assertThat(plan.shippingChecks()).isNotEmpty();
            assertThat(plan.limits()).anyMatch(limit -> limit.contains("not provided"));
        }
        assertThat(guidance.plan("STATIC").steps()).anyMatch(step -> step.contains("STATIC Flow Version"));
        assertThat(guidance.plan("MULTIPLAYER").shippingChecks()).anyMatch(check -> check.contains("concurrent"));
        assertThat(guidance.build("A blog").messages()).hasSize(1);
        assertThat(guidance.repair("Build failed").messages()).hasSize(1);
        assertThatThrownBy(() -> guidance.plan("NATIVE_WEBSOCKET")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guidance.build("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guidance.repair(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> guidance.build("x".repeat(8001))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void examplesRetainSharedFixturesAndCorrectHeaderGuidance() {
        var examples = new FunctionExampleMcpTools();
        assertThat(examples.getFunctionExample("NODE_BASIC").files().get(0).content()).isEqualTo(FunctionExampleFixtures.NODE_BASIC_SOURCE);
        assertThat(examples.getFunctionExample("NODE_BASIC").description()).contains("Optional headers work");
        assertThat(examples.getFunctionExample("STATIC_MULTIPAGE").files()).isEqualTo(FunctionExampleFixtures.staticMultipageFiles());
        assertThat(examples.getFunctionExample("NODE_REQUEST_HEADERS").files().get(0).content()).contains("Set-Cookie");
    }

    @Test
    void toolBudgetsArePerUserAndResetWithoutSessionState() {
        var clock = new AtomicLong();
        var limiter = new McpToolRateLimiter(2, clock::get);
        var alice = UUID.randomUUID();
        var bob = UUID.randomUUID();
        assertThat(limiter.tryAcquire(alice)).isTrue();
        assertThat(limiter.tryAcquire(alice)).isTrue();
        assertThat(limiter.tryAcquire(alice)).isFalse();
        assertThat(limiter.tryAcquire(bob)).isTrue();
        clock.addAndGet(TimeUnit.MINUTES.toNanos(1));
        assertThat(limiter.tryAcquire(alice)).isTrue();
        assertThatThrownBy(() -> new McpToolRateLimiter(0, clock::get)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyProductionToolStillGeneratesAUniqueSchemaWithValidGuidePointers() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var specs = context.getBean(McpToolCatalog.class).specifications();
            assertThat(specs).hasSize(11);
            assertThat(specs.stream().map(spec -> spec.tool().name()).toList()).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrder("discover", "read", "build_function", "compose_flow", "invoke", "publish_flow",
                            "configure", "connect_database", "configure_gateway", "claim_domain", "retire");
            var pointers = Pattern.compile("funchole://guides/([a-z]+)");
            var guides = context.getBean(McpGuideCatalog.class);
            specs.forEach(spec -> {
                assertThat(spec.tool().inputSchema()).isNotNull();
                assertThat(spec.tool().outputSchema()).isNotNull();
                pointers.matcher(spec.tool().description()).results().forEach(match ->
                        assertThat(guides.read(match.group(1))).isNotBlank());
            });
            assertThat(context.getBean(McpResourceCatalog.class).specifications()).hasSize(24);
            assertThat(context.getBean(McpResourceCatalog.class).templates()).hasSize(10);
            String serialized = io.modelcontextprotocol.json.McpJsonDefaults.getMapper()
                    .writeValueAsString(specs.stream().map(s -> s.tool()).toList());
            System.out.println("MCP declaration bytes=" + serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            System.out.println("MCP required top-level parameters=" + specs.stream().mapToInt(s ->
                    ((List<?>) s.tool().inputSchema().getOrDefault("required", List.of())).size()).sum());
            var discovery = context.getBean(McpToolCatalog.class).get("discover").tool();
            assertThat(discovery.inputSchema().toString()).contains("custom-domains", "function-versions", "flow-versions")
                    .doesNotContain("custom_domains", "function_versions", "flow_versions");
        }
    }

    @Test
    void referencesRejectAlternateAuthoritiesAndParentShapes() {
        String id = UUID.randomUUID().toString();
        for (String value : List.of("https://functions/" + id, "funchole://user@functions/" + id,
                "funchole://functions/" + id + "?user=other", "funchole://function-versions/" + id,
                "funchole://functions/" + id + "/extra", "funchole://functions/1-1-1-1-1")) {
            assertThatThrownBy(() -> McpReference.parse(value)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(McpReference.parse("funchole://functions/" + id).id().toString()).isEqualTo(id);
    }

    @Test
    void boundedTextIsLosslesslyResumable() throws Exception {
        String text = "x".repeat(35000);
        var first = (McpReadTools.Chunk) McpReadTools.bound(text, 0, 20000);
        var second = (McpReadTools.Chunk) McpReadTools.bound(text, first.nextOffset(), 20000);
        assertThat(first.text() + second.text()).isEqualTo(text);
        assertThat(second.nextOffset()).isNull();
    }

    @Test
    void taskAliasesFindCuratedGuidesAndExposeTheirTags() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var reads = context.getBean(McpReadTools.class);
            Map<String, String> targets = Map.of("html", "static", "frontend", "static", "api", "node",
                    "credentials", "data", "update", "evolve", "cookie", "node");
            targets.forEach((query, guide) -> {
                var page = (McpReadTools.Page) reads.discover(null, query, null, 0, 20).data();
                assertThat(page.items()).anySatisfy(item -> {
                    if (item instanceof McpReadTools.Knowledge knowledge
                            && knowledge.pointer().equals("funchole://guides/" + guide)) {
                        assertThat(knowledge.tags()).contains(query);
                    } else throw new AssertionError("Not the matching guide");
                });
            });
            var login = (McpReadTools.Page) reads.discover(null, "login", null, 0, 20).data();
            assertThat(login.items().stream().map(item -> ((McpReadTools.Knowledge) item).pointer()))
                    .contains("funchole://guides/node", "funchole://guides/multiplayer");
        }
    }

    @Test
    void continuationsNameExactToolArgumentsAndDisappearAtExhaustion() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var reads = context.getBean(McpReadTools.class);
            var first = reads.discover(null, null, null, 0, 2);
            assertThat(first.continuation().tool()).isEqualTo("discover");
            assertThat(first.continuation().unit()).isEqualTo("items");
            assertThat(first.continuation().nextArguments()).containsEntry("scope", "knowledge")
                    .containsEntry("offset", 2).containsEntry("limit", 2);
            var done = reads.discover(null, "xyzunmatchable123", null, 0, 2);
            assertThat(done.continuation()).isNull();

            var chunked = reads.read("funchole://guides/start", McpReadTools.View.state, 0, 100, null);
            var chunk = (McpReadTools.Chunk) chunked.data();
            assertThat(chunked.continuation().tool()).isEqualTo("read");
            assertThat(chunked.continuation().unit()).isEqualTo("characters");
            assertThat(chunked.continuation().nextArguments()).containsEntry("reference", "funchole://guides/start")
                    .containsEntry("view", "state").containsEntry("offset", chunk.nextOffset())
                    .containsEntry("maxChars", 100);
            var end = reads.read("funchole://guides/start", McpReadTools.View.state, chunk.totalChars(), 100, null);
            assertThat(((McpReadTools.Chunk) end.data()).nextOffset()).isNull();
            assertThat(end.continuation()).isNull();
        }
    }

    @Test
    void receiptActionsDescribeInspectionPollingAndManualHttpsProof() {
        String ref = McpReference.of("functions", UUID.randomUUID());
        var version = new FunctionVersionResponse(UUID.randomUUID(), UUID.randomUUID(), 1,
                FunctionVersionStatus.PUBLISHING, "NODE", null, null, null, null, null, null, null, null);
        var build = new McpOperationResult(true, "OK", "Build started; read state until READY or FAILED.", ref,
                version, Map.of("state", ref), List.of());
        assertThat(build.nextActions()).singleElement().satisfies(action -> {
            assertThat(action.kind()).isEqualTo("tool");
            assertThat(action.tool()).isEqualTo("read");
            assertThat(action.reference()).isEqualTo(ref);
            assertThat(action.view()).isEqualTo("state");
        });
        assertThat(McpOperationResult.failure("PARTIAL_FAILURE", "Inspect", ref).nextActions())
                .singleElement().extracting(McpOperationResult.NextAction::reference).isEqualTo(ref);
        var adopted = new FlowVersionResponse(UUID.randomUUID(), UUID.randomUUID(), 1,
                FlowVersionStatus.ADOPTED, "NODE", null, List.of(), null, null, null, null);
        var published = new McpOperationResult(true, "OK", "Revision adopted. External HTTPS remains unverified.", ref,
                adopted, Map.of("state", ref), List.of());
        assertThat(published.nextActions()).singleElement().satisfies(action -> {
            assertThat(action.kind()).isEqualTo("manual");
            assertThat(action.tool()).isNull();
            assertThat(action.message()).contains("external HTTPS");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedSchemasValidateNullableContinuationActionsAndExplicitNullExpectation() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var catalog = context.getBean(McpToolCatalog.class);
            var validator = io.modelcontextprotocol.json.McpJsonDefaults.getSchemaValidator();
            var buildSchema = catalog.get("build_function").tool().outputSchema();
            var receipt = McpOperationResult.failure("PARTIAL_FAILURE", "Inspect current state", McpReference.of("functions", UUID.randomUUID()));
            Map<String, Object> encoded = io.modelcontextprotocol.json.McpJsonDefaults.getMapper().convertValue(receipt,
                    new io.modelcontextprotocol.json.TypeRef<Map<String, Object>>() { });
            assertThat(validator.validate(buildSchema, encoded).valid()).isTrue();
            var safeFailure = McpIdempotency.call("build_function", null, Map.of(), null, () -> { throw new AssertionError(); });
            assertThat(validator.validate(buildSchema, safeFailure.structuredContent()).valid()).isTrue();

            var publish = catalog.get("publish_flow").tool();
            Map<String, Object> request = (Map<String, Object>) ((Map<String, Object>) publish.inputSchema().get("properties")).get("request");
            assertThat(validator.validate(request, Map.of("reference", "funchole://flow-versions/x/y",
                    "expectedActiveVersionRef", "none")).valid()).isTrue();
            var explicitNull = new java.util.LinkedHashMap<String, Object>();
            explicitNull.put("reference", "funchole://flow-versions/x/y");
            explicitNull.put("expectedActiveVersionRef", null);
            assertThat(validator.validate(request, explicitNull).valid()).isTrue();
            assertThat(validator.validate(request, Map.of("reference", "funchole://flow-versions/x/y")).valid()).isFalse();
        }
    }

    @Test
    void sourceLogsAndDependenciesContinueWithTheSameProjectionAndFile() {
        var versions = mock(FunctionVersionMcpTools.class);
        var flowVersions = mock(FlowVersionMcpTools.class);
        var reads = new McpReadTools(mock(McpGuideCatalog.class), mock(McpToolCatalog.class), new FunctionExampleMcpTools(),
                mock(FunctionMcpTools.class), versions, mock(FlowMcpTools.class), flowVersions,
                mock(GatewayMcpTools.class), mock(DomainMcpTools.class), mock(CustomDomainMcpTools.class),
                mock(DatabaseMcpTools.class), mock(EnvironmentProfileMcpTools.class), mock(FlowConfigurationMcpTools.class),
                mock(InvocationMcpTools.class));
        UUID parent = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        String sourceRef = McpReference.version("function-versions", parent, id);
        String dependencyRef = McpReference.version("flow-versions", parent, id);
        when(versions.getFunctionVersionSource(parent.toString(), id.toString())).thenReturn(
                new com.funchole.backend.controlplane.dto.FunctionVersionFullSourceResponse("index.mjs", "handler",
                        List.of(new com.funchole.backend.controlplane.dto.FunctionVersionSourceFileResponse("index.mjs", "x".repeat(260)))));
        when(versions.getFunctionVersionBuildLogs(parent.toString(), id.toString())).thenReturn(List.of(
                new com.funchole.backend.controlplane.dto.FunctionVersionBuildLogResponse("build", "npm run build", 0,
                        true, false, "x".repeat(260), "", null)));
        when(flowVersions.getFlowFullSource(parent.toString(), id.toString())).thenReturn(
                new FlowFullSourceResponse(parent, "x".repeat(260), id, 1, "DRAFT", List.of()));

        for (var view : List.of(McpReadTools.View.source, McpReadTools.View.logs, McpReadTools.View.dependencies)) {
            String ref = view == McpReadTools.View.dependencies ? dependencyRef : sourceRef;
            String file = view == McpReadTools.View.source ? "index.mjs" : null;
            var first = reads.read(ref, view, 0, 100, file);
            assertThat(first.ok()).isTrue();
            assertThat(first.continuation().unit()).isEqualTo("characters");
            assertThat(first.continuation().nextArguments()).containsEntry("reference", ref)
                    .containsEntry("view", view.name()).containsEntry("maxChars", 100);
            if (file != null) assertThat(first.continuation().nextArguments()).containsEntry("file", file);
            else assertThat(first.continuation().nextArguments()).doesNotContainKey("file");
            StringBuilder resumed = new StringBuilder();
            McpOperationResult page = first;
            while (page.continuation() != null) {
                resumed.append(((McpReadTools.Chunk) page.data()).text());
                int next = (int) page.continuation().nextArguments().get("offset");
                page = reads.read(ref, view, next, 100, file);
            }
            resumed.append(((McpReadTools.Chunk) page.data()).text());
            assertThat(resumed.toString()).contains("x".repeat(260));
        }
    }
}
