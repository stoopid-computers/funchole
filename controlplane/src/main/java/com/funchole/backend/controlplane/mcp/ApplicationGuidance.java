package com.funchole.backend.controlplane.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

@Service
public class ApplicationGuidance {
    public record ApplicationPlan(String kind, List<String> guideUris, List<String> prerequisites,
                                  List<String> steps, List<String> shippingChecks, List<String> limits) { }

    @McpTool(name = "plan_application", description = "Plan an app before mutations. Choose STATIC for a site, DYNAMIC for site + APIs, or MULTIPLAYER for shared state. Returns prerequisites, ordered building steps and shipping checks; does not provision or deploy anything.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public ApplicationPlan plan(
            @McpToolParam(description = "STATIC, DYNAMIC, or MULTIPLAYER") String kind
    ) {
        String normalized = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!List.of("STATIC", "DYNAMIC", "MULTIPLAYER").contains(normalized)) {
            throw new IllegalArgumentException("Choose STATIC, DYNAMIC, or MULTIPLAYER. Read the start guide for runtime selection.");
        }
        boolean dynamic = !normalized.equals("STATIC");
        return new ApplicationPlan(normalized,
                dynamic ? List.of("funchole://guides/start", "funchole://guides/static", "funchole://guides/node",
                        "funchole://guides/flows", "funchole://guides/data", "funchole://guides/" + (normalized.equals("MULTIPLAYER") ? "multiplayer" : "evolve"))
                        : List.of("funchole://guides/start", "funchole://guides/static", "funchole://guides/flows"),
                List.of("Reuse an owned Gateway or resolve verified base-domain, DNS and TLS access.",
                        dynamic ? "If persistence is needed, reuse or supply external Postgres; do not invent credentials."
                                : "Build tools must provide node/npm even for dependency-free STATIC source."),
                List.of("Inspect existing components and read the selected guides before authoring.",
                        "Fetch tested get_function_example scenarios for the chosen contracts.",
                        "Create Function + DRAFT Function Version; submit all files; deploy and poll to READY.",
                        dynamic ? "Build NODE APIs and STATIC UI separately; attach needed resources and validate draft NODE Invocations."
                                : "Use one STATIC artifact for all pages; a wildcard Flow Route and STATIC Flow Version with FUNCTION step.",
                        "Create matching runtime Flow Versions pinning READY components; NODE ends with RESPONSE.",
                        "Adopt only the tested revision, then verify real HTTPS using the host's HTTP/browser tools."),
                normalized.equals("MULTIPLAYER")
                        ? List.of("Two independent sessions, concurrent actions, idempotency, rejoin, and unauthorized room access.",
                                  "Working HTTPS UI/assets/API with observed shared state; disclose polling latency or external service requirement.")
                        : List.of("Working HTTPS URL; nested pages/assets or API envelope, status, headers/cookies and invalid inputs checked.",
                                  "Report tested URLs, resumable IDs, and unresolved operator checks."),
                List.of("Build/runtime code is not sandboxed; trusted source only.",
                        "Native WebSocket sessions, automatic retries and branching are not provided.",
                        "Plans are guidance, not an infrastructure health check or a promise of deployment."));
    }

    @McpPrompt(name = "build_application", description = "Build and ship an app idea using FuncHole's on-demand guides and tested examples.")
    public McpSchema.GetPromptResult build(
            @McpArg(name = "idea", description = "What the app should do", required = true) String idea
    ) {
        return prompt("Build an application", "App request:\n" + requireText(idea)
                + "\n\nRead funchole://guides/start via resources/read or get_funchole_guide. Choose a plan_application kind,"
                + " read its matching guides, reuse existing components, and perform the build/test/adopt/HTTP-check loop."
                + " Ask only for missing prerequisites or product decisions. Report what actually shipped and what is unverified.");
    }

    @McpPrompt(name = "repair_application", description = "Diagnose a failed build, Invocation or public URL before making a fix-forward revision.")
    public McpSchema.GetPromptResult repair(
            @McpArg(name = "symptom", description = "Observed failure and any known IDs or URL", required = true) String symptom
    ) {
        return prompt("Repair an application", "Observed problem:\n" + requireText(symptom)
                + "\n\nRead funchole://guides/troubleshooting. Inspect the real state/logs and current source before edits."
                + " Distinguish code failures from operator/DNS/TLS failures. Preserve the working live revision until its"
                + " replacement is tested. Exclude secrets from diagnostics and report evidence and remaining checks.");
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank() || value.length() > 8000) {
            throw new IllegalArgumentException("Provide non-empty text of at most 8000 characters.");
        }
        return value;
    }

    private static McpSchema.GetPromptResult prompt(String description, String text) {
        return McpSchema.GetPromptResult.builder(List.of(new McpSchema.PromptMessage(
                McpSchema.Role.USER, McpSchema.TextContent.builder(text).build()))).description(description).build();
    }
}
