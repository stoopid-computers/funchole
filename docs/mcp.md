# Connect an agent to FuncHole

The Streamable HTTP endpoint is `http://localhost:7080/api/mcp` in the development stack. For a remote deployment, use the operator's HTTPS controlplane URL, not a Gateway's application URL.

Create an MCP API key under **Agent Access** at `/api-keys` in the control-plane UI. That page displays the correct endpoint for the deployment, including any configured proxy URL. Configure the connecting host with that endpoint and `Authorization: Bearer <API key>`. Store the key in the host's secret facility; keep it out of source and shared config. The endpoint also accepts a valid controlplane JWT. It does not provide OAuth discovery or dynamic client registration.

Host configuration formats differ. Supply an HTTP/Streamable HTTP MCP server entry with this URL and Authorization header using your host's documented format. Browser clients must use an origin allowed by `app.cors.allowed-origins`. The allowlist includes MCP request headers and exposes legacy session headers. An absent Origin is allowed for non-browser agents; an unlisted Origin receives 403.

## Start an app

Server instructions direct the agent to `funchole://guides/start`. It can read that native resource or call `read` with `{"reference":"funchole://guides/start"}` when its host only supports tools. The STATIC, DYNAMIC, and MULTIPLAYER starting plans live in that guide, not a separate planning tool.

The production catalog has exactly eleven tools: `discover`, `read`, `build_function`, `compose_flow`, `invoke`, `publish_flow`, `configure`, `connect_database`, `configure_gateway`, `claim_domain`, and `retire`. There are no public legacy aliases.

| Task | Agent entry point |
| --- | --- |
| Find the right contract or operation | `discover` with task words in `query`; `read` the returned knowledge pointer |
| Plan before creating resources | Read the STATIC, DYNAMIC, or MULTIPLAYER plan in `funchole://guides/start` |
| Request an app-building workflow | Select the `build_application` prompt with an `idea` argument, if the host exposes prompts |
| Diagnose a failure | Select `repair_application` with `symptom`, or read the troubleshooting guide |
| Copy tested source | `read` a `funchole://examples/{SCENARIO}` URI for NODE_BASIC, NODE_DATABASE, NODE_ENV_VARS, NODE_REQUEST_HEADERS, or STATIC_MULTIPAGE |

Eight guides cover starting, STATIC, NODE, Flows/routes, data/configuration, multiplayer, troubleshooting, and extending/reusing apps. Native resources and `read` return the same packaged Markdown. Resource metadata also lists tool contracts and executable examples; clients must not assume the resource catalog has only eight entries. A guide cannot provision infrastructure or add native WebSocket support. Multiplayer guidance offers Postgres-backed HTTP polling and identifies when an external realtime service is required.

`discover` defaults to `scope="knowledge"`. Its optional `query` applies only to knowledge. Inventory scopes are `functions`, `flows`, `gateways`, `domains`, `custom-domains`, `databases`, `environments`, `function-versions`, and `flow-versions`; version scopes require a `parent` identity reference. Optional `offset` and `limit` page results, with `limit` no higher than 20. Follow `data.nextOffset` with the same limit until null. Results contain `data={items,total,nextOffset}`. Knowledge items contain `kind`, `name`, `description`, and `pointer`; inventory items contain `reference` and `summary`. Do not infer absence from one page.

Every tool returns `McpOperationResult` with `ok`, `code`, `message`, `reference`, `data`, `links`, and `warnings`. Check `ok` even when MCP `isError` is absent. A failed receipt may refer to partial work; inspect state before retrying. All writes wrap their response DTO in `data` and return the created or affected reference.

`read` requires `reference` and defaults to `view="state"`. Guidance URIs return a Markdown string in `data`; `funchole://tools/{name}` returns the actual `McpSchema.Tool`; example URIs return the existing fixture response; state URIs return the corresponding ResponseDto. Optional character `offset` and `maxChars` from 100 to 20000 bound reads. Oversized results become `data={text,mediaType,offset,nextOffset,totalChars}`. Continue with the same view/file and the returned character offset. Function Version `view="logs"` reads build logs. `view="source"` returns manifest paths unless `file` selects a source file. Read pinned dependencies before editing an existing app.

Server instructions remain under 1,200 characters and guide summaries under 240. Detailed guides and schemas load individually. Hosts that eagerly load every tool still pay the eleven-tool context cost. FuncHole cannot force a client to defer schemas.

## Build and publish with references

1. `build_function` takes a `request` with new `key`, `name`, `runtime`, `entrypoint`, `handler` when needed, complete `files` entries with `path`/`content`, and database references in `databases`. Updates instead identify `functionRef` and explicit `baseVersionRef`; omitted env/secrets inherit from that base. Files replace the source set. Poll `read` on the returned `funchole://function-versions/{functionId}/{id}` until `data.status` is READY. Bound polling; inspect FAILED logs rather than rebuilding blindly.
2. `compose_flow` takes a `request` with new `key`, `name`, `gatewayRef`, `httpMethod`, `path`, `priority`, matching `runtime`, and either one READY Function Version `componentRef` or explicit `steps`. For an existing Flow use `flowRef` and no route fields. It returns `funchole://flow-versions/{flowId}/{id}` with the FlowVersion DTO in `data`.
3. For NODE, `invoke` that draft reference with optional `input` as a raw JSON string. For an HTTP handler, serialize the actual request envelope yourself. Poll the returned Invocation reference and inspect result/errors. Invocation may change application data; do not use real moves or destructive migrations as a casual probe.
4. `publish_flow` takes a `request` with the Flow Version `reference` and `expectedActiveVersionRef="none"` initially, or the exact same-Flow active version reference on updates. Stale expectations conflict. An optional explicit `route` changes routing; omission preserves it. Verify actual HTTPS pages/assets or API status/body/headers/cookies after publication. Direct invocation does not verify DNS, TLS, or browser sessions.

`connect_database` registers supplied Postgres credentials, not a server. `configure` manages shared configuration and attachments. `configure_gateway` creates a Gateway with `name`, verified `domainRef`, and `status="ACTIVE"`. `claim_domain` takes `kind="BASE"` or `"CUSTOM"`, `hostname`, and `gatewayRef` for a new custom claim. Write returned DNS records externally, then check an existing `reference` with `check=true`. Domain state includes `id`, `domainName`, and `verificationCode`; claims may also wrap state with exact DNS requirements. `retire` is destructive; read its contract and obtain approval before use.

Gateway deletion and custom-domain detach through `retire` are permanent. Other identity retirement uses existing soft deletion. Flow Version `DELETE` applies only to DRAFT; `ARCHIVE` only to ADOPTED. Each action requires `allowLiveChanges=true`.

Read each exact `funchole://tools/{name}` contract before configuration or lifecycle changes. Never put controlplane keys or database credentials into browser bundles, source, logs, or learning notes. Builds and NODE handlers currently execute without a host sandbox.

## Protocol compatibility

One endpoint supports these revisions:

| Revision | Wire behavior |
| --- | --- |
| 2026-07-28 | Stateless per-request metadata, mirrored headers, server/discover, complete result envelopes, private cache hints |
| 2025-11-25 | Legacy initialize, session header, tools/resources/prompts through the Java SDK |
| 2025-06-18 | Same legacy lifecycle with the requested revision negotiated |
| 2025-03-26 | Legacy Streamable HTTP lifecycle; initial request does not need a version header |

The implementation uses Spring AI 2.0.1 and overrides its Java MCP SDK to 2.0.1. The SDK handles the legacy revisions. `McpProtocolFilter` and `ModernMcpProtocol` add the 2026 wire behavior around the same registered tool callbacks and authenticated services. They do not create a second deployment API. Authentication, authorization and origin checks precede modern dispatch.

The 2026 adapter implements server/discover, ping, tools/list and tools/call, resources/list, resources/templates/list with ten owned-state templates, resources/read, prompts/list and prompts/get. It advertises tools/resources/prompts only. It does not advertise sampling, elicitation, roots, logging, subscriptions or tasks. Builds and Invocations return references to poll with `read`. Unsupported methods return HTTP 404 with code -32601. Only knowledge resources get cache hints; mutable resource state is not cached.

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

These tests exercise real HTTP with the production security chain and fixture tool callbacks. They check all four revisions, native/read parity, bounded discovery, schemas, prompts, repeated calls from two users with per-request identity, authentication, origins, malformed requests, pagination, body limits and safe modern errors. They do not exercise real database ownership enforcement, builds, runtime deployment or application HTTPS traffic.

With Python 3 installed, set `FUNCHOLE_TEST_MCP_SMOKE=true` on that test command to also run the smoke script against the ephemeral HTTP fixture server. Without that environment variable, the Python-dependent test skips.

For a running stack, set `FUNCHOLE_MCP_API_KEY` securely in the environment, then run:

```bash
python3 scripts/dev/mcp-smoke.py --url http://localhost:7080/api/mcp
```

The script uses Python's standard library and performs only discovery/read calls. It checks all four revisions, exactly eleven production tools, native/read guide parity, paged knowledge pointers, exact contracts, examples, resource metadata, and two prompts. The HTTP fixture may add only `fixture_` tools; unexpected tools and legacy aliases fail the check. Identity isolation remains covered by the existing two-user HTTP fixture tests. The script does not build, publish, reveal passwords or invoke app code. Credentials are neither printed nor accepted as command-line arguments. It refuses redirects and uses normal TLS verification. Legacy sessions expire under the SDK's normal timeout policy.

After protocol checks, run the existing fixture-backed deployment tests with Docker and supporting services, then verify a real STATIC URL and NODE API. Do not mark an app shipped from discovery tests alone.

`scripts/orbstack/verify.py` is a mutating check restricted to the disposable `funchole-test` VM. Initial publication uses three mutation contracts, `build_function`, `compose_flow`, and `publish_flow`, plus bounded build reads. It invokes the draft NODE API before publication with a payload that initializes its test schema but inserts no moves. Real DNS verification, hostname-validated HTTPS, STATIC pages/assets, two independent HTTP cookie sessions with multiple Set-Cookie headers, and Postgres persistence after Runtime restart remain separate checks. Only the disposable certificate bootstrap bypasses validation; subsequent requests pin that certificate and validate the hostname. This is not production CA validation.

After reading `funchole://guides/evolve`, the VM check writes observed facts, references, and pending production checks to `.vm/mcp-runbook.md`. This is project-local evidence in a disposable workspace, not MCP-owned memory. Connecting agents may save app-specific skills or runbooks only with permission to write those project files; they must not modify global instructions or record secrets.

## Maintain guidance

Edit `controlplane/src/main/resources/mcp/guides/` and add catalog entries in `McpGuideCatalog`. Keep descriptions specific about when to read a guide. Reuse `FunctionExampleFixtures` for executable examples instead of copying source into prose. Add a guide link or prerequisite only after checking the service/runtime behavior and extend `McpGuidanceTests` for new contracts. Future callbacks that require a connection-scoped exchange need explicit modern support before exposure in the adapter.

The replacement design and checks are tracked in [the MCP primitives plan](mcp-primitives-plan.md), with [Convex findings](research/2026-10-02-convex-mcp-abstractions.md) and [Pi's discovery mechanisms](research/2026-10-02-pi-self-discovery-analysis.md). The [earlier plan](mcp-redesign-plan.md) records the superseded 75-tool implementation.
