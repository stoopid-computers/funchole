package com.funchole.backend.controlplane.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.ToolInputValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stateless 2026 wire semantics around the same Spring AI annotated callbacks. */
@Component
public class ModernMcpProtocol {
    public static final String VERSION = "2026-07-28";
    public static final List<String> VERSIONS = List.of(VERSION, "2025-11-25", "2025-06-18", "2025-03-26");
    static final String VERSION_META = "io.modelcontextprotocol/protocolVersion";
    static final String CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities";
    private static final Logger log = LoggerFactory.getLogger(ModernMcpProtocol.class);
    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() { };
    private static final int PAGE_SIZE = 20;

    private final McpToolCatalog tools;
    private final McpGuideCatalog guides;
    private final ObjectProvider<List<SyncPromptSpecification>> promptProviders;
    private final String instructions;
    private final Map<String, Object> serverInfo;
    private final McpJsonMapper mapper = McpJsonDefaults.getMapper();

    public ModernMcpProtocol(McpToolCatalog tools, McpGuideCatalog guides, ObjectProvider<List<SyncPromptSpecification>> promptProviders,
            @Value("${spring.ai.mcp.server.instructions}") String instructions,
            @Value("${spring.ai.mcp.server.name}") String name,
            @Value("${spring.ai.mcp.server.version}") String version) {
        this.tools = tools;
        this.guides = guides;
        this.promptProviders = promptProviders;
        this.instructions = instructions;
        this.serverInfo = Map.of("name", name, "version", version);
    }

    public record Reply(int status, Map<String, Object> body) { }

    public Reply dispatch(Map<String, Object> request) {
        Object id = request.get("id");
        try {
            validateEnvelope(request);
            Map<String, Object> params = object(request.get("params"), "params");
            Map<String, Object> meta = object(params.get("_meta"), "_meta");
            String version = text(meta.get(VERSION_META), VERSION_META);
            object(meta.get(CAPABILITIES_META), CAPABILITIES_META);
            if (!VERSION.equals(version)) {
                throw new Fault(400, -32022, "Unsupported protocol version",
                        Map.of("requested", version, "supported", VERSIONS));
            }
            String method = text(request.get("method"), "method");
            Map<String, Object> result = switch (method) {
                case "server/discover" -> cache(Map.of("supportedVersions", VERSIONS,
                        "capabilities", Map.of("tools", Map.of(), "resources", Map.of(), "prompts", Map.of()),
                        "instructions", instructions));
                case "ping" -> Map.of();
                case "tools/list" -> cache(page("tools", tools.specifications().stream().map(s -> s.tool()).toList(), params));
                case "tools/call" -> callTool(params, meta);
                case "resources/list" -> cache(page("resources", guides.specifications().stream().map(s -> s.resource()).toList(), params));
                case "resources/templates/list" -> cache(Map.of("resourceTemplates", List.of()));
                case "resources/read" -> readResource(params);
                case "prompts/list" -> cache(page("prompts", promptSpecifications().stream().map(s -> s.prompt()).toList(), params));
                case "prompts/get" -> getPrompt(params);
                default -> throw new Fault(404, -32601, "Method not found", Map.of());
            };
            Map<String, Object> complete = new LinkedHashMap<>(result);
            complete.put("resultType", "complete");
            Map<String, Object> resultMeta = complete.get("_meta") instanceof Map<?, ?>
                    ? object(complete.get("_meta"), "result _meta") : new LinkedHashMap<>();
            resultMeta.put("io.modelcontextprotocol/serverInfo", serverInfo);
            complete.put("_meta", resultMeta);
            return new Reply(200, Map.of("jsonrpc", "2.0", "id", id, "result", complete));
        } catch (Fault fault) {
            return error(id, fault.status, fault.code, fault.getMessage(), fault.data);
        } catch (IllegalArgumentException exception) {
            return error(id, 400, -32602, "Invalid params. Check the method's documented arguments.", Map.of());
        } catch (Exception exception) {
            log.error("Unexpected MCP protocol failure", exception);
            return error(id, 500, -32603, "Internal error. Inspect operator logs; do not repeat a mutation blindly.", Map.of());
        }
    }

    private Map<String, Object> callTool(Map<String, Object> params, Map<String, Object> meta) {
        String name = text(params.get("name"), "name");
        var spec = tools.specifications().stream().filter(s -> s.tool().name().equals(name)).findFirst()
                .orElseThrow(() -> new Fault(400, -32602, "Unknown tool. Use search_funchole.", Map.of()));
        Map<String, Object> arguments = params.containsKey("arguments") ? object(params.get("arguments"), "arguments") : Map.of();
        var invalid = ToolInputValidator.validate(spec.tool(), arguments, true, McpJsonDefaults.getSchemaValidator());
        if (invalid != null) return asMap(invalid);
        // Current FuncHole methods use CurrentMcpUser, not a connection-scoped exchange.
        // A future method needing sampling/elicitation must add a real modern implementation.
        try {
            var result = spec.callHandler().apply(null, new McpSchema.CallToolRequest(name, arguments, meta));
            // Spring AI converts RuntimeExceptions into error results with the root-cause
            // message, so catching thrown exceptions alone does not protect credentials.
            return asMap(Boolean.TRUE.equals(result.isError()) ? safeToolFailure() : result);
        } catch (RuntimeException exception) {
            log.warn("MCP tool failed: {}", name, exception);
            return asMap(safeToolFailure());
        }
    }

    private static McpSchema.CallToolResult safeToolFailure() {
        return McpSchema.CallToolResult.builder().isError(true)
                .content(List.of(McpSchema.TextContent.builder(
                        "Tool failed. Read get_funchole_guide('troubleshooting') and inspect the resource's state before retrying. "
                                + "Check its exact input contract with get_funchole_tool; ask the operator to inspect logs if the cause is unclear.").build()))
                .build();
    }

    private Map<String, Object> readResource(Map<String, Object> params) {
        String uri = text(params.get("uri"), "uri");
        if (guides.guides().stream().noneMatch(g -> g.uri().equals(uri))) {
            throw new Fault(400, -32602, "Unknown resource. List resources or use search_funchole.", Map.of());
        }
        return cache(asMap(guides.readResource(uri)));
    }

    private Map<String, Object> getPrompt(Map<String, Object> params) {
        String name = text(params.get("name"), "name");
        var spec = promptSpecifications().stream().filter(p -> p.prompt().name().equals(name)).findFirst()
                .orElseThrow(() -> new Fault(400, -32602, "Unknown prompt. List prompts first.", Map.of()));
        Map<String, Object> raw = params.containsKey("arguments") ? object(params.get("arguments"), "arguments") : Map.of();
        Map<String, Object> arguments = new LinkedHashMap<>();
        raw.forEach((key, value) -> arguments.put(key, text(value, key)));
        for (var argument : spec.prompt().arguments()) {
            if (Boolean.TRUE.equals(argument.required()) && !arguments.containsKey(argument.name())) {
                throw new Fault(400, -32602, "Missing prompt argument: " + argument.name(), Map.of());
            }
        }
        var request = new McpSchema.GetPromptRequest(name, arguments, null);
        return asMap(spec.promptHandler().apply(null, request));
    }

    private Map<String, Object> asMap(Object value) { return mapper.convertValue(value, MAP_TYPE); }

    private List<SyncPromptSpecification> promptSpecifications() {
        return promptProviders.stream().flatMap(List::stream).toList();
    }

    private static Map<String, Object> cache(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>(value);
        result.put("ttlMs", 300000);
        // Authenticated metadata may change with future tenant-specific capabilities.
        result.put("cacheScope", "private");
        return result;
    }

    private static Map<String, Object> page(String key, List<?> values, Map<String, Object> params) {
        int start = 0;
        if (params.containsKey("cursor")) {
            String cursor = text(params.get("cursor"), "cursor");
            try { start = Integer.parseInt(cursor); }
            catch (NumberFormatException exception) { throw new Fault(400, -32602, "Invalid cursor", Map.of()); }
            if (start < 0 || start > values.size()) throw new Fault(400, -32602, "Invalid cursor", Map.of());
        }
        int end = Math.min(start + PAGE_SIZE, values.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(key, new ArrayList<>(values.subList(start, end)));
        if (end < values.size()) result.put("nextCursor", String.valueOf(end));
        return result;
    }

    static void validateEnvelope(Map<String, Object> request) {
        Object id = request.get("id");
        if (!"2.0".equals(request.get("jsonrpc")) || !(request.get("method") instanceof String)
                || !(id instanceof String || id instanceof Integer || id instanceof Long)) {
            throw new Fault(400, -32600, "Expected one JSON-RPC 2.0 request with a string or integer id", Map.of());
        }
    }

    static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new Fault(400, -32602, "Expected non-empty string: " + field, Map.of());
        }
        return text;
    }

    static Map<String, Object> object(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) throw new Fault(400, -32602, "Expected object: " + field, Map.of());
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String name)) throw new Fault(400, -32602, "Expected string keys: " + field, Map.of());
            result.put(name, item);
        });
        return result;
    }

    static Reply error(Object id, int status, int code, String message, Map<String, Object> data) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (!data.isEmpty()) error.put("data", data);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (id instanceof String || id instanceof Integer || id instanceof Long) body.put("id", id);
        body.put("error", error);
        return new Reply(status, body);
    }

    static final class Fault extends RuntimeException {
        final int status;
        final int code;
        final Map<String, Object> data;
        Fault(int status, int code, String message, Map<String, Object> data) {
            super(message);
            this.status = status;
            this.code = code;
            this.data = data;
        }
    }
}
