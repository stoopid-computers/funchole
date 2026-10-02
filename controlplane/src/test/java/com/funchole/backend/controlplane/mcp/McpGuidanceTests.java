package com.funchole.backend.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
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
}
