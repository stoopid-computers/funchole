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
    @Import({McpGuideCatalog.class, McpToolCatalog.class, McpDiscoveryTools.class, ApplicationGuidance.class})
    static class Fixture {
        @Bean
        List<SyncToolSpecification> testToolSpecs(McpDiscoveryTools discovery, ApplicationGuidance guidance) {
            return new SyncMcpToolProvider(List.of(discovery, guidance, new FunctionExampleMcpTools())).getToolSpecifications();
        }
    }

    @Test
    void everyGuideHasTheSameBodyThroughResourceAndToolAndAllLinksResolve() {
        try (var context = new AnnotationConfigApplicationContext(Fixture.class)) {
            var guides = context.getBean(McpGuideCatalog.class);
            var discovery = context.getBean(McpDiscoveryTools.class);
            var references = Pattern.compile("funchole://guides/([a-z]+)");
            for (var guide : guides.guides()) {
                assertThat(guide.description()).hasSizeLessThanOrEqualTo(240);
                String body = guides.read(guide.topic());
                assertThat(body).startsWith("# ");
                assertThat(discovery.getGuide(guide.uri())).isEqualTo(body);
                var resource = guides.readResource(guide.uri()).contents().get(0);
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
            var discovery = context.getBean(McpDiscoveryTools.class);
            var first = discovery.search("", 0, 2);
            assertThat(first.matches()).hasSize(2);
            assertThat(first.total()).isGreaterThan(2);
            assertThat(first.nextOffset()).isEqualTo(2);
            var next = discovery.search(null, first.nextOffset(), 2);
            assertThat(next.matches()).doesNotContainAnyElementsOf(first.matches());
            assertThat(discovery.search("nothingmatchesxyz", null, null).matches()).isEmpty();
            assertThat(discovery.search(null, Integer.MAX_VALUE, 20).matches()).isEmpty();
            assertThat(discovery.search("static", null, null).matches()).anyMatch(m -> m.pointer().equals("funchole://guides/static"));
            assertThat(discovery.search("scenario", null, null).matches()).anyMatch(m -> m.name().equals("get_function_example"));
            assertThat(discovery.getTool("get_function_example").inputSchema()).containsKey("properties");
            assertThatThrownBy(() -> discovery.search(null, -1, 2)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> discovery.search(null, 0, 21)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> discovery.search("x".repeat(301), 0, 2)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> discovery.getTool("invented_tool")).isInstanceOf(IllegalArgumentException.class);
            assertThat(discovery.search(null, null, 20).matches()).allSatisfy(m ->
                    assertThat(m.description()).hasSizeLessThanOrEqualTo(240));
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
            var beans = new ArrayList<Object>();
            for (Class<?> type : List.of(FunctionMcpTools.class, FunctionVersionMcpTools.class, FlowMcpTools.class,
                    FlowVersionMcpTools.class, GatewayMcpTools.class, DomainMcpTools.class, CustomDomainMcpTools.class,
                    DatabaseMcpTools.class, EnvironmentProfileMcpTools.class, FlowConfigurationMcpTools.class,
                    InvocationMcpTools.class, FunctionExampleMcpTools.class)) {
                var constructor = type.getConstructors()[0];
                // Generate real annotation schemas, without invoking mocked business dependencies.
                Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(dependency -> mock(dependency)).toArray();
                beans.add(constructor.newInstance(dependencies));
            }
            beans.add(context.getBean(McpDiscoveryTools.class));
            beans.add(context.getBean(ApplicationGuidance.class));
            var specs = new SyncMcpToolProvider(beans).getToolSpecifications();
            assertThat(specs).hasSize(75);
            assertThat(specs.stream().map(spec -> spec.tool().name()).toList()).doesNotHaveDuplicates()
                    .contains("get_invocation", "submit_function_version_source", "adopt_flow_version", "get_function_example");
            var pointers = Pattern.compile("get_funchole_guide\\('([a-z]+)'");
            var guides = context.getBean(McpGuideCatalog.class);
            specs.forEach(spec -> {
                assertThat(spec.tool().inputSchema()).isNotNull();
                pointers.matcher(spec.tool().description()).results().forEach(match ->
                        assertThat(guides.read(match.group(1))).isNotBlank());
            });
        }
    }
}
