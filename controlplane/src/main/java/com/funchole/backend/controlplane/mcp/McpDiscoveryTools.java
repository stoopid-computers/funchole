package com.funchole.backend.controlplane.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class McpDiscoveryTools {
    private final McpGuideCatalog guides;
    private final McpToolCatalog tools;

    public McpDiscoveryTools(McpGuideCatalog guides, McpToolCatalog tools) {
        this.guides = guides;
        this.tools = tools;
    }

    public record Match(String kind, String name, String description, String pointer) { }
    public record SearchResult(List<Match> matches, int total, Integer nextOffset) { }

    @McpTool(name = "get_funchole_guide", description = "Read task guidance before building or fixing an app. Start with topic 'start'; use search_funchole for specific topics. Same content as native guide resources.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public String getGuide(@McpToolParam(description = "Guide topic or exact funchole://guides/... URI") String topic) {
        return guides.read(topic);
    }

    @McpTool(name = "get_funchole_tool", description = "Fetch one real tool's exact input/output schema. Use after search_funchole when you need its parameter contract; this lookup does not execute it.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public McpSchema.Tool getTool(@McpToolParam(description = "Exact tool name returned by search_funchole") String name) {
        return tools.get(name).tool();
    }

    @McpTool(name = "search_funchole", description = "Discover guides and tools by task, such as static website, database, route, cookie, or deploy failure. Returns bounded summaries and pointers; read a matching guide or fetch one tool schema next.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SearchResult search(
            @McpToolParam(description = "Task words, at most 300 characters; empty lists the catalog", required = false) String query,
            @McpToolParam(description = "0-based continuation offset, default 0", required = false) Integer offset,
            @McpToolParam(description = "Result count, 1 to 20, default 8", required = false) Integer limit
    ) {
        int start = offset == null ? 0 : offset;
        int count = limit == null ? 8 : limit;
        if (start < 0 || count < 1 || count > 20) {
            throw new IllegalArgumentException("Use offset >= 0 and limit from 1 to 20.");
        }
        if (query != null && query.length() > 300) {
            throw new IllegalArgumentException("Use a search query of at most 300 characters.");
        }
        String[] words = query == null || query.isBlank() ? new String[0]
                : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<Match> entries = new ArrayList<>();
        guides.guides().forEach(g -> entries.add(new Match("guide", g.topic(), g.description(), g.uri())));
        tools.specifications().forEach(spec -> entries.add(new Match("tool", spec.tool().name(),
                spec.tool().description(), spec.tool().name())));
        List<Match> ranked = entries.stream()
                .filter(entry -> words.length == 0 || score(entry, words) > 0)
                .sorted(Comparator.<Match>comparingInt(entry -> score(entry, words)).reversed()
                        .thenComparing(Match::kind).thenComparing(Match::name)).toList();
        int end = (int) Math.min((long) start + count, ranked.size());
        List<Match> page = start >= ranked.size() ? List.of() : ranked.subList(start, end).stream()
                .map(entry -> new Match(entry.kind(), entry.name(), summarize(entry.description()), entry.pointer())).toList();
        return new SearchResult(page, ranked.size(), end < ranked.size() ? end : null);
    }

    private int score(Match match, String[] words) {
        String text = (match.name() + " " + match.description()).toLowerCase(Locale.ROOT);
        if (match.kind().equals("tool")) {
            text += " " + tools.get(match.name()).tool().inputSchema().toString().toLowerCase(Locale.ROOT);
        }
        int score = 0;
        for (String word : words) {
            if (!word.isBlank() && text.contains(word)) score += match.kind().equals("guide") ? 3 : 1;
        }
        return score;
    }

    private static String summarize(String text) {
        return text.length() <= 240 ? text : text.substring(0, 237) + "...";
    }
}
