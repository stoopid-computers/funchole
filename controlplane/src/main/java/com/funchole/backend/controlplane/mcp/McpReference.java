package com.funchole.backend.controlplane.mcp;

import java.net.URI;
import java.util.UUID;

/** Routing references, not capability tokens. Services must still check ownership. */
public record McpReference(String kind, UUID parentId, UUID id) {
    public static String of(String kind, UUID id) { return "funchole://" + kind + "/" + id; }
    public static String version(String kind, UUID parent, UUID id) {
        return "funchole://" + kind + "/" + parent + "/" + id;
    }

    public static McpReference parse(String value) {
        if (value == null) throw new IllegalArgumentException("A resource reference is required");
        URI uri = URI.create(value);
        if (!"funchole".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Use a returned funchole resource reference");
        }
        String[] parts = uri.getPath().split("/", -1);
        boolean version = uri.getHost().equals("function-versions") || uri.getHost().equals("flow-versions");
        if (parts.length != (version ? 3 : 2) || !parts[0].isEmpty()) {
            throw new IllegalArgumentException("Invalid resource reference");
        }
        return new McpReference(uri.getHost(), version ? uuid(parts[1]) : null, uuid(parts[version ? 2 : 1]));
    }

    private static UUID uuid(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException("Use a canonical UUID");
        return id;
    }

    public McpReference require(String expected) {
        if (!kind.equals(expected)) throw new IllegalArgumentException("Unexpected resource kind");
        return this;
    }
}
