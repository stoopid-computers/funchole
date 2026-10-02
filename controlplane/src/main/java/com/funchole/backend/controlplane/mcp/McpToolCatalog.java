package com.funchole.backend.controlplane.mcp;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Reads the same registered specifications used by Spring AI's legacy server. */
@Component
public class McpToolCatalog {
    private final ObjectProvider<List<SyncToolSpecification>> providers;
    private volatile List<SyncToolSpecification> specifications;

    public McpToolCatalog(ObjectProvider<List<SyncToolSpecification>> providers) { this.providers = providers; }

    public synchronized List<SyncToolSpecification> specifications() {
        if (specifications == null) {
            specifications = providers.stream().flatMap(List::stream)
                    .sorted(Comparator.comparing(spec -> spec.tool().name())).toList();
        }
        return specifications;
    }

    public SyncToolSpecification get(String name) {
        return specifications().stream().filter(spec -> spec.tool().name().equals(name))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Unknown tool. Call search_funchole to find the exact tool name."));
    }
}
