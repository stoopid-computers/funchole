package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.service.McpClientOperationService;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** MCP receipt encoding and safe conflict messages around the transport-neutral coordinator. */
public final class McpIdempotency {
    private static final String INSPECT = "Operation may have run. Inspect current state before taking further action; do not repeat it with this ID.";

    private McpIdempotency() { }

    public static McpSchema.CallToolResult call(String tool, Object id, Map<String, Object> arguments,
            McpClientOperationService operations, Supplier<McpSchema.CallToolResult> action) {
        if (!(id instanceof String value)) return failure("INVALID_INPUT", "clientOperationId must be 1 to 128 safe characters.");
        if (operations == null) return failure("INVALID_INPUT", "clientOperationId is unavailable in this context.");
        var outcome = operations.execute(CurrentMcpUser.id(), tool, value, arguments, () -> {
            try {
                return McpJsonDefaults.getMapper().writeValueAsString(action.get());
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot encode MCP operation receipt", exception);
            }
        });
        return switch (outcome.status()) {
            case INVALID_ID -> failure("INVALID_INPUT", "clientOperationId must be 1 to 128 safe characters.");
            case CHANGED_REQUEST -> failure("CONFLICT", "clientOperationId already belongs to a different request.");
            case IN_PROGRESS -> failure("CONFLICT", INSPECT);
            case COMPLETED -> replay(outcome.receiptJson());
        };
    }

    private static McpSchema.CallToolResult replay(String receiptJson) {
        try {
            return McpJsonDefaults.getMapper().readValue(receiptJson, McpSchema.CallToolResult.class);
        } catch (IOException exception) {
            return failure("CONFLICT", INSPECT);
        }
    }

    private static McpSchema.CallToolResult failure(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code);
        body.put("message", message);
        body.put("reference", null);
        body.put("data", null);
        body.put("links", Map.of("guide", "funchole://guides/troubleshooting"));
        body.put("warnings", List.of());
        body.put("continuation", null);
        body.put("nextActions", List.of(Map.of("kind", "tool", "tool", "read",
                "reference", "funchole://guides/troubleshooting", "view", "state",
                "message", "Read troubleshooting guidance and inspect current state.")));
        return McpSchema.CallToolResult.builder().isError(true).structuredContent(body)
                .content(List.of(McpSchema.TextContent.builder(message).build())).build();
    }
}
