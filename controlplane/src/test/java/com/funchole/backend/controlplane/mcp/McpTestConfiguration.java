package com.funchole.backend.controlplane.mcp;

import static org.mockito.Mockito.mock;
import java.util.Arrays;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** Real curated declarations with mocked business dependencies, never a live tenant. */
@TestConfiguration(proxyBeanMethods = false)
class McpTestConfiguration {
    @Bean McpReadTools reads(McpGuideCatalog guides, McpToolCatalog tools) {
        return new McpReadTools(guides, tools, new FunctionExampleMcpTools(), mock(FunctionMcpTools.class),
                mock(FunctionVersionMcpTools.class), mock(FlowMcpTools.class), mock(FlowVersionMcpTools.class),
                mock(GatewayMcpTools.class), mock(DomainMcpTools.class), mock(CustomDomainMcpTools.class),
                mock(DatabaseMcpTools.class), mock(EnvironmentProfileMcpTools.class), mock(FlowConfigurationMcpTools.class),
                mock(InvocationMcpTools.class));
    }
    @Bean McpWorkflowTools workflows() { return construct(McpWorkflowTools.class); }
    @Bean McpInfrastructureTools infrastructure() { return construct(McpInfrastructureTools.class); }

    static <T> T construct(Class<T> type) {
        try {
            var constructor = type.getConstructors()[0];
            Object[] args = Arrays.stream(constructor.getParameterTypes()).map(t -> mock(t)).toArray();
            return type.cast(constructor.newInstance(args));
        } catch (ReflectiveOperationException exception) { throw new IllegalStateException(exception); }
    }
}
