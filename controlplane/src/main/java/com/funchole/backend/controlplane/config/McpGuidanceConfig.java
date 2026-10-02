package com.funchole.backend.controlplane.config;

import com.funchole.backend.controlplane.mcp.ApplicationGuidance;
import com.funchole.backend.controlplane.mcp.McpInfrastructureTools;
import com.funchole.backend.controlplane.mcp.McpReadTools;
import com.funchole.backend.controlplane.mcp.McpResourceCatalog;
import com.funchole.backend.controlplane.mcp.McpWorkflowTools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.springframework.ai.mcp.annotation.provider.prompt.SyncMcpPromptProvider;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpGuidanceConfig {
    @Bean
    public List<SyncResourceSpecification> funcholeGuideResources(McpResourceCatalog catalog) {
        return catalog.specifications();
    }

    @Bean
    public List<SyncResourceTemplateSpecification> funcholeResourceTemplates(McpResourceCatalog catalog) {
        return catalog.templates();
    }

    @Bean
    public List<SyncToolSpecification> funcholeTools(McpReadTools reads, McpWorkflowTools workflows, McpInfrastructureTools infrastructure) {
        return toolSpecifications(List.of(reads, workflows, infrastructure));
    }

    public static List<SyncToolSpecification> toolSpecifications(List<Object> objects) {
        return new SyncMcpToolProvider(objects).getToolSpecifications().stream().map(spec -> new SyncToolSpecification(
                receiptTool(spec.tool()), (exchange, request) -> {
                    var result = spec.callHandler().apply(exchange, request);
                    if (result.structuredContent() instanceof java.util.Map<?, ?> body && Boolean.FALSE.equals(body.get("ok"))) {
                        return McpSchema.CallToolResult.builder().content(result.content()).structuredContent(result.structuredContent()).isError(true).build();
                    }
                    return result;
                })).toList();
    }

    @SuppressWarnings("unchecked") // Generator-owned JSON Schema maps, not caller input.
    private static McpSchema.Tool receiptTool(McpSchema.Tool tool) {
        Map<String, Object> input = tool.inputSchema();
        if (tool.name().equals("discover")) {
            // The generator uses Java enum names, ignoring Jackson's wire aliases.
            var properties = new java.util.LinkedHashMap<>((Map<String, Object>) input.get("properties"));
            var scope = new java.util.LinkedHashMap<>((Map<String, Object>) properties.get("scope"));
            scope.put("enum", java.util.Arrays.stream(McpReadTools.Scope.values()).map(s -> s.name().replace('_', '-')).toList());
            properties.put("scope", scope);
            input = new java.util.LinkedHashMap<>(input);
            input.put("properties", properties);
        }
        // Spring's generator marks Nullable fields optional but not nullable. The SDK
        // validates serialized nulls, so publish the actual shared receipt contract.
        Map<String, Object> output = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("ok", "code", "message", "reference", "data", "links", "warnings"),
                "properties", Map.of("ok", Map.of("type", "boolean"),
                        "code", Map.of("type", "string", "enum", List.of("OK", "INVALID_INPUT", "NOT_FOUND", "NOT_AUTHORIZED", "CONFLICT", "PARTIAL_FAILURE", "OPERATION_FAILED")),
                        "message", Map.of("type", "string"), "reference", Map.of("type", List.of("string", "null")),
                        "data", Map.of(), "links", Map.of("type", "object", "additionalProperties", Map.of("type", "string")),
                        "warnings", Map.of("type", "array", "items", Map.of("type", "string"))));
        return new McpSchema.Tool(tool.name(), tool.title(), tool.description(), input, output,
                tool.annotations(), tool.meta(), tool.icons());
    }

    @Bean
    public List<SyncPromptSpecification> funcholePrompts(ApplicationGuidance guidance) {
        return new SyncMcpPromptProvider(List.of(guidance)).getPromptSpecifications();
    }
}
