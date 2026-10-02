package com.funchole.backend.controlplane.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceTemplateSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.springframework.stereotype.Component;

/** Native resources and the tools-only read path share one content resolver. */
@Component
public class McpResourceCatalog {
    private final McpReadTools reads;
    public McpResourceCatalog(McpReadTools reads) { this.reads = reads; }

    public List<SyncResourceSpecification> specifications() {
        return reads.knowledge().stream().map(k -> new SyncResourceSpecification(
                McpSchema.Resource.builder(k.pointer(), k.name()).description(k.description())
                        .mimeType(k.kind().equals("guide") ? "text/markdown" : "application/json").build(),
                (exchange, request) -> read(request.uri()))).toList();
    }

    public List<SyncResourceTemplateSpecification> templates() {
        return List.of("functions", "flows", "gateways", "domains", "custom-domains", "databases", "environments", "invocations",
                "function-versions", "flow-versions").stream().map(kind -> {
            String uri = "funchole://" + kind + (kind.endsWith("-versions") ? "/{parent}/{id}" : "/{id}");
            return new SyncResourceTemplateSpecification(McpSchema.ResourceTemplate.builder(uri, kind)
                    .description("Owned resource state. Use read for explicit source, configuration or diagnostic projections and continuation.")
                    .mimeType("application/json").build(), (exchange, request) -> read(request.uri()));
        }).toList();
    }

    public McpSchema.ReadResourceResult read(String uri) {
        var result = reads.read(uri, null, null, null, null);
        if (!result.ok()) throw new IllegalArgumentException("Resource unavailable. Discover an owned reference or known guide.");
        try {
            String content = result.data() instanceof String text ? text : McpJsonDefaults.getMapper().writeValueAsString(result.data());
            return McpSchema.ReadResourceResult.builder(List.of(McpSchema.TextResourceContents.builder(uri, content)
                    .mimeType(uri.startsWith("funchole://guides/") ? "text/markdown" : "application/json").build())).build();
        } catch (Exception exception) {
            throw new IllegalStateException("Resource serialization failed");
        }
    }
}
