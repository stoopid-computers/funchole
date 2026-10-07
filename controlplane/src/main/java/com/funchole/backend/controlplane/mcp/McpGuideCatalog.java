package com.funchole.backend.controlplane.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** One catalog for resource discovery, tool fallback, and app plans. */
@Component
public class McpGuideCatalog {

    public record Guide(String topic, String description) {
        public String uri() { return "funchole://guides/" + topic; }
    }

    private static final List<Guide> GUIDES = List.of(
            new Guide("start", "Build and ship an app from an idea. Read first for runtime choice, prerequisites, tool order, safety, and shipping checks."),
            new Guide("static", "Build websites, SPAs, multi-page sites and shared assets. Read before submitting STATIC source or publishing its wildcard route."),
            new Guide("node", "Write Node APIs. Read before authoring handlers, parsing HTTP input, returning status/body/headers, or using cookies."),
            new Guide("flows", "Compose pinned steps and publish HTTP routes. Read for Gateway/DNS/TLS setup, path matching, adoption, and route updates."),
            new Guide("data", "Use supplied Postgres databases, migrations, shared configuration and secrets. Read before persistence or environment setup."),
            new Guide("multiplayer", "Build shared state with HTTP polling, or identify external realtime requirements. Read for games, rooms, presence, or collaboration."),
            new Guide("troubleshooting", "Diagnose build, runtime, source-storage and public HTTP failures. Read when an operation fails or stays pending."),
            new Guide("evolve", "Extend apps, reuse deployed components and retain tested project-specific procedures. Read before updates or writing learning notes."));

    public List<Guide> guides() { return GUIDES; }

    public String read(String topicOrUri) {
        Guide guide = GUIDES.stream()
                .filter(g -> g.topic().equals(topicOrUri) || g.uri().equals(topicOrUri))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Unknown guide. Use discover or read funchole://guides/start."));
        try {
            return new ClassPathResource("mcp/guides/" + guide.topic() + ".md").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Packaged MCP guide is unavailable: " + guide.topic(), exception);
        }
    }

    public List<SyncResourceSpecification> specifications() {
        return GUIDES.stream().map(guide -> new SyncResourceSpecification(
                McpSchema.Resource.builder(guide.uri(), guide.topic())
                        .description(guide.description()).mimeType("text/markdown").build(),
                (exchange, request) -> readResource(request.uri()))).toList();
    }

    public McpSchema.ReadResourceResult readResource(String uri) {
        return McpSchema.ReadResourceResult.builder(List.of(
                McpSchema.TextResourceContents.builder(uri, read(uri)).mimeType("text/markdown").build())).build();
    }
}
