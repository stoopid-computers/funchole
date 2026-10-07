package com.funchole.backend.controlplane.config;

import com.funchole.backend.controlplane.mcp.ApplicationGuidance;
import com.funchole.backend.controlplane.mcp.McpInfrastructureTools;
import com.funchole.backend.controlplane.mcp.McpReadTools;
import com.funchole.backend.controlplane.mcp.McpResourceCatalog;
import com.funchole.backend.controlplane.mcp.McpWorkflowTools;
import com.funchole.backend.controlplane.mcp.McpIdempotency;
import com.funchole.backend.controlplane.service.McpClientOperationService;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.springframework.ai.mcp.annotation.provider.prompt.SyncMcpPromptProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

@Configuration
public class McpGuidanceConfig {
    private static final Set<String> MUTATIONS = Set.of("build_function", "compose_flow", "invoke", "publish_flow",
            "configure", "connect_database", "configure_gateway", "claim_domain", "retire");
    @Bean
    public List<SyncResourceSpecification> funcholeGuideResources(McpResourceCatalog catalog) {
        return catalog.specifications();
    }

    @Bean
    public List<SyncResourceTemplateSpecification> funcholeResourceTemplates(McpResourceCatalog catalog) {
        return catalog.templates();
    }

    @Bean
    public List<SyncToolSpecification> funcholeTools(McpReadTools reads, McpWorkflowTools workflows,
            McpInfrastructureTools infrastructure, ObjectProvider<McpClientOperationService> operations) {
        return toolSpecifications(List.of(reads, workflows, infrastructure), operations.getIfAvailable());
    }

    public static List<SyncToolSpecification> toolSpecifications(List<Object> objects) {
        return toolSpecifications(objects, null);
    }

    private static List<SyncToolSpecification> toolSpecifications(List<Object> objects, McpClientOperationService operations) {
        return new SyncMcpToolProvider(objects).getToolSpecifications().stream().map(spec -> new SyncToolSpecification(
                receiptTool(spec.tool()), (exchange, request) -> {
                    var arguments = request.arguments();
                    boolean supplied = arguments != null && arguments.containsKey("clientOperationId");
                    Object id = arguments == null ? null : arguments.get("clientOperationId");
                    Map<String, Object> delegated = arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
                    delegated.remove("clientOperationId");
                    var cleanRequest = new McpSchema.CallToolRequest(request.name(), delegated, request.meta());
                    java.util.function.Supplier<McpSchema.CallToolResult> action = () -> normalize(spec.callHandler().apply(exchange, cleanRequest));
                    if (MUTATIONS.contains(spec.tool().name()) && supplied) {
                        return McpIdempotency.call(spec.tool().name(), id, delegated, operations, action);
                    }
                    return action.get();
                })).toList();
    }

    private static McpSchema.CallToolResult normalize(McpSchema.CallToolResult result) {
        if (result.structuredContent() instanceof java.util.Map<?, ?> body && Boolean.FALSE.equals(body.get("ok"))) {
            return McpSchema.CallToolResult.builder().content(result.content()).structuredContent(result.structuredContent()).isError(true).build();
        }
        return result;
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
        if (MUTATIONS.contains(tool.name())) {
            var properties = new LinkedHashMap<>((Map<String, Object>) input.get("properties"));
            properties.put("clientOperationId", Map.of("type", "string", "minLength", 1, "maxLength", 128,
                    "pattern", "^[A-Za-z0-9._:-]+$", "description", "Optional client operation ID. Reusing it with the same request replays a completed receipt; uncertain runs require inspection."));
            input = new LinkedHashMap<>(input);
            input.put("properties", properties);
        }
        if (tool.name().equals("publish_flow")) {
            var properties = new LinkedHashMap<>((Map<String, Object>) input.get("properties"));
            var request = new LinkedHashMap<>((Map<String, Object>) properties.get("request"));
            var requestProperties = new LinkedHashMap<>((Map<String, Object>) request.get("properties"));
            var expected = new LinkedHashMap<>((Map<String, Object>) requestProperties.get("expectedActiveVersionRef"));
            expected.put("type", List.of("string", "null"));
            requestProperties.put("expectedActiveVersionRef", expected);
            request.put("properties", requestProperties);
            var required = new java.util.ArrayList<>((List<String>) request.getOrDefault("required", List.of()));
            if (!required.contains("expectedActiveVersionRef")) required.add("expectedActiveVersionRef");
            request.put("required", required);
            properties.put("request", request);
            input = new LinkedHashMap<>(input);
            input.put("properties", properties);
        }
        // Spring's generator marks Nullable fields optional but not nullable. The SDK
        // validates serialized nulls, so publish the actual shared receipt contract.
        Map<String, Object> output = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("ok", "code", "message", "reference", "data", "links", "warnings", "continuation", "nextActions"),
                "properties", Map.of("ok", Map.of("type", "boolean"),
                        "code", Map.of("type", "string", "enum", List.of("OK", "INVALID_INPUT", "NOT_FOUND", "NOT_AUTHORIZED", "CONFLICT", "PARTIAL_FAILURE", "OPERATION_FAILED")),
                        "message", Map.of("type", "string"), "reference", Map.of("type", List.of("string", "null")),
                        "data", Map.of(), "links", Map.of("type", "object", "additionalProperties", Map.of("type", "string")),
                        "warnings", Map.of("type", "array", "items", Map.of("type", "string")),
                        "continuation", Map.of("type", List.of("object", "null"), "additionalProperties", false,
                                "required", List.of("tool", "unit", "nextArguments"),
                                "properties", Map.of("tool", Map.of("type", "string", "enum", List.of("discover", "read")),
                                        "unit", Map.of("type", "string", "enum", List.of("items", "characters")),
                                        "nextArguments", Map.of("type", "object"))),
                        "nextActions", Map.of("type", "array", "items", Map.of("type", "object", "additionalProperties", false,
                                "required", List.of("kind", "tool", "reference", "view", "message"),
                                "properties", Map.of("kind", Map.of("type", "string", "enum", List.of("tool", "manual")),
                                        "tool", Map.of("type", List.of("string", "null")),
                                        "reference", Map.of("type", List.of("string", "null")),
                                        "view", Map.of("type", List.of("string", "null")),
                                        "message", Map.of("type", "string"))))));
        return new McpSchema.Tool(tool.name(), tool.title(), tool.description(), input, output,
                tool.annotations(), tool.meta(), tool.icons());
    }

    @Bean
    public List<SyncPromptSpecification> funcholePrompts(ApplicationGuidance guidance) {
        return new SyncMcpPromptProvider(List.of(guidance)).getPromptSpecifications();
    }
}
