package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.mcp.FunctionExampleFixtures.ExampleFile;
import java.util.List;
import java.util.Locale;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * MCP tool surface for {@link FunctionExampleFixtures} - a backup for a stuck coding agent:
 * copy-pasteable, known-working example source for a given scenario, instead of requiring the
 * agent to correctly synthesize behavior from prose tool descriptions alone (see
 * MCP_TESTING_FEEDBACK.md §8). {@link com.funchole.backend.controlplane.functionbuild.BuildFailureException}
 * references this tool by name in every build-failure message an agent can hit, since that is
 * the moment an agent is actually stuck and looking for a way out - not the tool list, which
 * items 1-6 of that same feedback file showed agents don't reliably consult on their own.
 */
@Service
public class FunctionExampleMcpTools {

    public record FunctionExampleResponse(
            String scenario,
            String runtime,
            String description,
            String entrypoint,
            String handler,
            List<ExampleFile> files
    ) {
    }

    @McpTool(
            name = "get_function_example",
            description = "Fetch fixture-backed source before your first handler/site submission or when stuck. "
                    + "Returns entrypoint, handler, runtime and files directly usable by submit_function_version_source. "
                    + "Read get_funchole_guide('static', 'node' or 'data') for the complete build-to-HTTP journey."
    )
    public FunctionExampleResponse getFunctionExample(
            @McpToolParam(description = "NODE_BASIC, NODE_DATABASE, NODE_ENV_VARS, NODE_REQUEST_HEADERS, or STATIC_MULTIPAGE") String scenario
    ) {
        String normalized = scenario == null ? "" : scenario.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "NODE_BASIC" -> new FunctionExampleResponse(
                    "NODE_BASIC",
                    "NODE",
                    "A NODE RESPONSE handler returning {status, body}. The Gateway JSON-serializes body and "
                            + "defaults to application/json. Optional headers work; use NODE_REQUEST_HEADERS for "
                            + "that example and get_funchole_guide('node') for real HTTP input and response contracts.",
                    FunctionExampleFixtures.NODE_BASIC_ENTRYPOINT,
                    FunctionExampleFixtures.NODE_BASIC_HANDLER,
                    List.of(new ExampleFile(FunctionExampleFixtures.NODE_BASIC_ENTRYPOINT, FunctionExampleFixtures.NODE_BASIC_SOURCE))
            );
            case "NODE_DATABASE" -> new FunctionExampleResponse(
                    "NODE_DATABASE",
                    "NODE",
                    "A NODE-runtime Function that reads/writes a Database resource attached via "
                            + "attach_function_version_database. context.db(name) returns a real node-postgres "
                            + "(pg) Pool - call .query(sql, params) on it directly. There is no separate "
                            + "migration/seed tool: this handler's own CREATE TABLE IF NOT EXISTS is the pattern "
                            + "for getting an initial schema into a freshly attached Database - deploy it and "
                            + "invoke it once.",
                    FunctionExampleFixtures.NODE_DATABASE_ENTRYPOINT,
                    FunctionExampleFixtures.NODE_DATABASE_HANDLER,
                    List.of(new ExampleFile(FunctionExampleFixtures.NODE_DATABASE_ENTRYPOINT, FunctionExampleFixtures.NODE_DATABASE_SOURCE))
            );
            case "NODE_ENV_VARS" -> new FunctionExampleResponse(
                    "NODE_ENV_VARS",
                    "NODE",
                    "A NODE-runtime Function reading a config value from process.env. There is no context.env - "
                            + "every FunctionVersion env var/secret (set via set_function_version_env_var/"
                            + "set_function_version_secret) and every var/secret from an EnvironmentProfile "
                            + "attached to the Flow (attach_flow_environment) is merged into a single map and set "
                            + "onto process.env immediately before this specific invocation runs, then restored "
                            + "afterward - read it exactly like this example does. Precedence on a key collision, "
                            + "highest wins: FunctionVersion secret > FunctionVersion env var > attached "
                            + "EnvironmentProfile secret > attached EnvironmentProfile env var (and between two "
                            + "attached profiles, the one with the higher attach_flow_environment priority wins). "
                            + "Set GREETING with set_function_version_env_var before invoking.",
                    FunctionExampleFixtures.NODE_ENV_VARS_ENTRYPOINT,
                    FunctionExampleFixtures.NODE_ENV_VARS_HANDLER,
                    List.of(new ExampleFile(FunctionExampleFixtures.NODE_ENV_VARS_ENTRYPOINT, FunctionExampleFixtures.NODE_ENV_VARS_SOURCE))
            );
            case "NODE_REQUEST_HEADERS" -> new FunctionExampleResponse(
                    "NODE_REQUEST_HEADERS",
                    "NODE",
                    "A NODE RESPONSE step reading the incoming request's headers/cookies and setting response "
                            + "ones. input.headers is every request header as {name: [value, ...]} (a header can "
                            + "legitimately repeat, so it's always an array, even for one value) with hop-by-hop "
                            + "headers already stripped; input.cookies is the Cookie header pre-parsed into a plain "
                            + "{name: value} map for convenience - the raw header is still in input.headers.Cookie "
                            + "too. To set response headers, add a headers field alongside status/body in the "
                            + "return value: a value can be a single string or an array of strings, and an array is "
                            + "required for Set-Cookie if you need more than one - joining multiple cookies into "
                            + "one comma-separated string breaks every cookie after the first. Content-Length and "
                            + "Transfer-Encoding can't be overridden this way (the Gateway always computes them "
                            + "itself); every other header, including Content-Type, can be. Only meaningful for a "
                            + "Flow reached through the Gateway over real HTTP - see this tool's own scenario "
                            + "description for why direct invocation doesn't apply here.",
                    FunctionExampleFixtures.NODE_REQUEST_HEADERS_ENTRYPOINT,
                    FunctionExampleFixtures.NODE_REQUEST_HEADERS_HANDLER,
                    List.of(new ExampleFile(FunctionExampleFixtures.NODE_REQUEST_HEADERS_ENTRYPOINT, FunctionExampleFixtures.NODE_REQUEST_HEADERS_SOURCE))
            );
            case "STATIC_MULTIPAGE" -> new FunctionExampleResponse(
                    "STATIC_MULTIPAGE",
                    "STATIC",
                    "A real multi-page static site as ONE Function/FunctionVersion submission - not one Function "
                            + "per page. index.html serves '/', about.html serves '/about', blog/index.html "
                            + "serves '/blog', blog/first-post.html serves '/blog/first-post'. package.json's "
                            + "\"build\" script runs for real (npm ci/install, then npm run build, always, in that "
                            + "order) and must produce a dist/ directory with this same file layout inside it - "
                            + "cp/mkdir in \"build\" is exactly what this example does and is fully supported, "
                            + "nothing STATIC-specific to work around.",
                    FunctionExampleFixtures.STATIC_ENTRYPOINT,
                    null,
                    FunctionExampleFixtures.staticMultipageFiles()
            );
            default -> throw new IllegalArgumentException(
                    "Unknown scenario: '" + scenario + "' - expected one of NODE_BASIC, NODE_DATABASE, NODE_ENV_VARS, "
                            + "NODE_REQUEST_HEADERS, STATIC_MULTIPAGE");
        };
    }
}
