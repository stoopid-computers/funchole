# Connect an agent to FuncHole

The Streamable HTTP endpoint is `http://localhost:7080/api/mcp` in the development stack. For a remote deployment, use the operator's HTTPS controlplane URL, not a Gateway's application URL.

Create an MCP API key under **Agent Access** at `/api-keys` in the control-plane UI. That page displays the correct endpoint for the deployment, including any configured proxy URL. Configure the connecting host with that endpoint and `Authorization: Bearer <API key>`. Store the key in the host's secret facility; keep it out of source and shared config. The endpoint also accepts a valid controlplane JWT. It does not provide OAuth discovery or dynamic client registration.

Host configuration formats differ. Supply an HTTP/Streamable HTTP MCP server entry with this URL and Authorization header using your host's documented format. Browser clients must use an origin allowed by `app.cors.allowed-origins`. The allowlist includes MCP request headers and exposes legacy session headers. An absent Origin is allowed for non-browser agents; an unlisted Origin receives 403.

## Start an app

Server instructions direct the agent to `funchole://guides/start`. It can read that resource or call `get_funchole_guide` with `{"topic":"start"}` when its host only supports tools.

| Task | Agent entry point |
| --- | --- |
| Find the right contract or operation | `search_funchole` with task words; follow a guide pointer or fetch `get_funchole_tool` for one schema |
| Plan before creating resources | `plan_application` with STATIC, DYNAMIC, or MULTIPLAYER |
| Request an app-building workflow | Select the `build_application` prompt with an `idea` argument, if the host exposes prompts |
| Diagnose a failure | Select `repair_application` with `symptom`, or read the troubleshooting guide |
| Copy tested source | `get_function_example` with NODE_BASIC, NODE_DATABASE, NODE_ENV_VARS, NODE_REQUEST_HEADERS, or STATIC_MULTIPAGE |

Eight guides cover starting, STATIC, NODE, Flows/routes, data/configuration, multiplayer, troubleshooting, and extending/reusing apps. Resources and the tool fallback read the same packaged Markdown. A guide cannot provision infrastructure or add native WebSocket support. Multiplayer guidance offers Postgres-backed HTTP polling and identifies when an external realtime service is required.

Search returns at most 20 summaries with `total` and `nextOffset`. Server instructions remain under 1,200 characters and guide summaries under 240. Detailed guides and schemas load individually. Hosts that eagerly load every tool still pay the tool-list context cost. FuncHole cannot force a client to defer schemas.

## Protocol compatibility

One endpoint supports these revisions:

| Revision | Wire behavior |
| --- | --- |
| 2026-07-28 | Stateless per-request metadata, mirrored headers, server/discover, complete result envelopes, private cache hints |
| 2025-11-25 | Legacy initialize, session header, tools/resources/prompts through the Java SDK |
| 2025-06-18 | Same legacy lifecycle with the requested revision negotiated |
| 2025-03-26 | Legacy Streamable HTTP lifecycle; initial request does not need a version header |

The implementation uses Spring AI 2.0.1 and overrides its Java MCP SDK to 2.0.1. The SDK handles the legacy revisions. `McpProtocolFilter` and `ModernMcpProtocol` add the 2026 wire behavior around the same registered tool callbacks and authenticated services. They do not create a second deployment API. Authentication, authorization and origin checks precede modern dispatch.

The 2026 adapter implements server/discover, ping, tools/list and tools/call, resources/list, resources/templates/list with an empty list, resources/read, prompts/list and prompts/get. It advertises tools/resources/prompts only. It does not advertise sampling, elicitation, roots, logging, subscriptions or tasks. Builds and Invocations return normal IDs to poll. Unsupported methods return HTTP 404 with code -32601.

Every modern request needs an object `params._meta` with:

- `io.modelcontextprotocol/protocolVersion`, set to `2026-07-28`.
- `io.modelcontextprotocol/clientCapabilities`, set to an object, often `{}`.
- Optional `io.modelcontextprotocol/clientInfo`, with client name and version.

Also send `Content-Type: application/json`, `Accept: application/json, text/event-stream`, `MCP-Protocol-Version: 2026-07-28`, and `Mcp-Method` matching the method. tools/call and prompts/get need `Mcp-Name` matching `params.name`; resources/read needs it matching `params.uri`. Encoded Mcp-Name values use the protocol's UTF-8 Base64 sentinel. The current tools do not designate custom `x-mcp-header` parameters.

Header mismatches return HTTP 400 with code -32020; unsupported modern versions return 400 with -32022 and supported versions. Missing metadata returns 400 with -32602. Modern GET/DELETE requests receive 405. Modern requests ignore legacy session and replay headers and never mint a session. POST bodies are limited to 8 MiB for both eras. Tool argument validation uses the SDK JSON Schema validator. Modern callback failures return `isError=true` with a guide pointer rather than internal exception text; legacy error conversion remains the SDK's behavior.

Both eras share a per-user tool-call budget of 120 calls per minute by default. `MCP_TOOL_CALLS_PER_MINUTE` changes it. Exceeding the budget returns HTTP 429, application error code 1001, and `Retry-After: 60`. The budget is in-memory per controlplane process, not a distributed quota or a runtime isolation control. Discovery lists and resource reads do not consume the tool-call budget.

OAuth, process sandboxing, persistent Gateway request correlation and production infrastructure hardening are separate work. See [known limitations](limitations.md) and the guides' safety boundary.

## Repeatable checks

With Java 25, run the non-container tests:

```bash
./gradlew :controlplane:test --tests '*McpGuidanceTests' --tests '*McpCompatibilityHttpTests'
```

These tests exercise real HTTP with the production security chain and fixture tool callbacks. They check all four revisions, resource/tool parity, bounded discovery, schemas, prompts, per-request user identity, authentication, origins, malformed requests, pagination, body limits and safe modern errors. They do not exercise real database ownership enforcement, builds, runtime deployment or application HTTPS traffic.

With Python 3 installed, set `FUNCHOLE_TEST_MCP_SMOKE=true` on that test command to also run the smoke script against the ephemeral HTTP fixture server. Without that environment variable, the Python-dependent test skips.

For a running stack, set `FUNCHOLE_MCP_API_KEY` securely in the environment, then run:

```bash
python3 scripts/dev/mcp-smoke.py --url http://localhost:7080/api/mcp
```

The script uses Python's standard library and performs only discovery/guidance calls. It checks all four revisions and follows pagination, but does not submit source, deploy, adopt, reveal passwords or invoke app code. Credentials are neither printed nor accepted as command-line arguments. It refuses redirects and uses normal TLS verification. Legacy sessions expire under the SDK's normal timeout policy.

After protocol checks, run the existing fixture-backed deployment tests with Docker and supporting services, then verify a real STATIC URL and NODE API. Do not mark an app shipped from discovery tests alone.

## Maintain guidance

Edit `controlplane/src/main/resources/mcp/guides/` and add catalog entries in `McpGuideCatalog`. Keep descriptions specific about when to read a guide. Reuse `FunctionExampleFixtures` for executable examples instead of copying source into prose. Add a guide link or prerequisite only after checking the service/runtime behavior and extend `McpGuidanceTests` for new contracts. Future callbacks that require a connection-scoped exchange need explicit modern support before exposure in the adapter.

The design and remaining checks are tracked in [the MCP redesign plan](mcp-redesign-plan.md), with [FuncHole findings](research/2026-10-02-funchole-mcp-current-state.md) and [Pi's discovery mechanisms](research/2026-10-02-pi-self-discovery-analysis.md).
