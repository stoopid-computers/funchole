# MCP redesign plan

## Goal and boundary

An agent connecting to FuncHole should discover how to turn an app idea into deployed Functions and live Flow Routes without asking the user to learn the platform. Keep existing tool names and ownership checks. Teach the agent the build, test, publish, inspect, and fix-forward loop through on-demand guides.

Documentation cannot add missing execution capabilities. STATIC sites, Node APIs, external Postgres attachments, headers/cookies, shared configuration, and ordered Flows work today. Multiplayer can use database-backed HTTP polling, or a separately provisioned realtime service. Native WebSocket sessions, branching, and automatic retries are not offered by this redesign.

## Evidence

- [Pi analysis](research/2026-10-02-pi-self-discovery-analysis.md): Pi advertises descriptions and paths, loads bodies on demand, and extends itself by authoring files. It does not have an autonomous self-improvement subsystem.
- [FuncHole research](research/2026-10-02-funchole-mcp-current-state.md): existing tools mostly mirror REST CRUD. Long descriptions duplicate contracts and already contradict newer header support.
- [Testing feedback](../MCP_TESTING_FEEDBACK.md): STATIC deployment, source formats, response contracts, missing npm, source durability, migrations, and unsafe execution caused repeated guessing. Some reported bugs have already been fixed. Distinguish those from current limitations.

## Design

### Discovery and guidance

Use short server instructions pointing to a start guide. Publish guides as native MCP resources with descriptions stating when to read them. Also expose a guide-reading tool for hosts that only support tools. Both return the same content. Search guides and the actual annotated tool catalog, returning bounded metadata instead of every schema. Fetch one exact schema when needed.

Split guides by task: start, static sites, Node HTTP contracts, Flows/routes, databases/configuration, multiplayer, troubleshooting, and extending/reusing an application. Cross-reference guides with full resource URIs and point to the existing tested example tool. Keep secrets out of docs. Keep essential safety and replacement semantics next to mutation tools even when moving long tutorials into guides.

Add an app-planning tool and user-invoked build/repair prompts. Plans name prerequisites, execution steps, tests, and shipping criteria; they do not create resources or pretend infrastructure exists. Existing deployment and adoption tools remain the executable building blocks. Reuse deployed components and inspect source before updates. Persist project-specific learning in the connecting agent's project files when that host permits file writes; changing shared server guidance remains a reviewed code change.

### Protocol compatibility

Support the latest revision, 2026-07-28, plus the three earlier revisions 2025-11-25, 2025-06-18, and 2025-03-26. The existing Spring AI 2.0.1 integration uses the Java SDK's legacy initialize/session protocol. Preserve those three 2025 revisions and any older revisions the SDK already supports.

Add a narrowly scoped, stateless 2026-07-28 HTTP adapter on the same endpoint. Reuse Spring AI's tool specifications, schemas, argument conversion, callbacks, and existing authenticated services. Do not create a second business API. Validate per-request metadata and mirrored headers; implement server/discover, tool/resource/prompt discovery and calls, complete result envelopes, cache guidance, and specified error responses. Advertise only implemented optional capabilities. No sampling, elicitation, subscriptions, or tasks are claimed. Async builds and Invocations continue returning ordinary IDs to poll.

Use the existing CORS origin allowlist and authentication chain for both eras. Include MCP request/exposed response headers in CORS. Bound request bodies. Error responses must not expose internal stack traces or credentials.

Implementation limits are an 8 MiB POST body and a configurable per-user budget of 120 tool calls per minute per process. The modern adapter sanitizes callback error results as well as thrown exceptions because Spring AI can embed root-cause messages in `isError` results. Legacy callback error conversion remains the SDK's behavior.

Primary protocol sources:
- https://modelcontextprotocol.io/specification/2026-07-28/basic/index
- https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning
- https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http
- https://modelcontextprotocol.io/specification/2026-07-28/server/discover
- https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1

### Context budget

Server instructions stay under 1,200 characters. Guide summaries stay under 240 characters. Search returns at most 20 entries, with a total and continuation offset. Detailed guides and exact tool schemas are loaded individually. Existing tool schemas remain visible to clients that eagerly load tools; MCP does not let a server force Pi-style client-side deferral. Search is an additional discovery path, not a claim that all clients now have zero tool-list cost.

## Checklist

### Research and model
- [x] Inspect Pi's prompt, skills, and extension discovery against primary sources.
- [x] Read current MCP classes and testing feedback; distinguish live contracts from stale descriptions.
- [x] Capture domain terms without implementation details in CONTEXT.md.
- [x] Save FuncHole findings and current tool inventory with source citations.
- [x] Verify the latest protocol revision, three earlier revisions, and SDK boundary against primary sources.

### Implement discovery
- [x] Add a single guide catalog with task descriptions and Markdown bodies.
- [x] Register native resources and a tool fallback from that catalog.
- [x] Add bounded search over guides and real tools, plus exact-schema lookup.
- [x] Add read-only application planning and user-invoked build/repair prompts.
- [x] Replace long duplicated tutorials with guide pointers; correct headers/cookies guidance.
- [x] Describe reusable components, project-local learning, current capability limits, and shipping checks.

### Implement protocol coverage
- [x] Add a stateless 2026-07-28 adapter reusing Spring AI tool specifications.
- [x] Preserve legacy initialization, sessions, and tool/resource/prompt operations.
- [x] Validate metadata, headers, JSON-RPC envelopes, method arguments, and body limits.
- [x] Keep authentication and origin checks before dispatch; update MCP CORS headers.
- [x] Add modern discovery, caching metadata, and version-specific error behavior.
- [x] Rate-limit tool calls per authenticated user without using connection/session identity.

### Verify and document
- [x] Test guide/resource/tool parity, links, search bounds, schemas, plans, and prompt arguments.
- [x] Test stateless requests, mismatches, invalid input, errors, authentication, origins, and per-request user identity.
- [x] Test legacy initialization and tools/resources/prompts for 2025-11-25, 2025-06-18, and 2025-03-26.
- [x] Compile and run focused tests; record environmental blockers honestly.
- [x] Update connection/development docs and add repeatable compatibility smoke checks.
- [x] Run the Python smoke script against the ephemeral HTTP fixture, including all four revisions.
- [x] Review changed app-contract claims against service, Gateway and Runtime code.
- [x] Boot the full application and run database-backed ownership/deployment tests with Docker.
- [x] Verify real STATIC and NODE HTTPS URLs through the deployed Gateway.
- [x] Run a two-session multiplayer journey using real shared persistence.

## Verification record

2026-10-02: baseline and redesigned controlplane compile with Java 25. All 18 focused guidance and real-HTTP compatibility tests pass, including the opt-in Python smoke test. Schema generation preserves all 71 existing tools and adds four discovery/planning tools. The production bootJar builds and contains all eight guides and SDK 2.0.1 jars. The HTTP fixture uses production security configuration and Spring AI registration, but mocked authentication lookup and fixture callbacks. Its per-request identity tests do not prove database ownership enforcement or deployment correctness.

Command used, with Java 25 supplied through JAVA_HOME:

```bash
FUNCHOLE_TEST_MCP_SMOKE=true ./gradlew :controlplane:test --tests '*McpGuidanceTests' --tests '*McpCompatibilityHttpTests'
```

At this initial stage Docker's socket was unavailable. No live stack was restarted, no app resources were mutated, and no credentials were revealed. Full-stack and public-URL checks were still pending. [Connection and smoke-test docs](mcp.md) describe how to repeat both levels of verification.

2026-10-02, subsequent isolated-VM verification: installed Alpine 3.23.6 arm64 and OpenJDK 25.0.4 in the new `funchole-test` OrbStack machine. Built and deployed the current jars with the production Compose dependencies, using fresh VM-only credentials and persistent initialized OpenBao. All deployment services are healthy; the Mac can reach the Control Plane health endpoint and admin login page. No existing host stack was restarted.

The deployed MCP endpoint passes discovery, guides, resources and prompts for all four revisions, reporting 75 tools, eight resources and two prompts each. A mutating MCP journey passes real DNS TXT verification, STATIC home/second-page/asset checks, and a NODE API through the deployed Gateway. HTTPS requests validate the hostname against a locally pinned self-signed certificate. This does not prove public DNS or ACME issuance. Two independent cookie sessions exchange moves through a dedicated real Postgres database; both moves remain after restarting the deployed Runtime. The secret-free result is `/opt/funchole/.vm/verification.json` inside the VM.

Environment fixes included Docker's `fuse-overlayfs` storage driver, replacing the unavailable `minio/minio:latest` E2E fixture with pinned RustFS, installing the Node executor's dependencies, and isolating the test services from the deployment. The zero-to-HTTP E2E fixture now polls asynchronous deployment to READY rather than asserting READY immediately. [VM setup and repeatable checks](orbstack-testing.md) document these prerequisites.

The complete Control Plane run passes 263 non-E2E tests and all four E2E tests. This includes real database-backed ownership/deployment checks, the focused MCP suite, the opt-in compatibility smoke fixture and the source-to-Gateway HTTP E2E test. Two non-E2E Invocation tests require a Dispatcher and Runtime consuming the dedicated test NATS server; without them, their Invocations stay PENDING. Supplying those isolated components resolves both failures without changing their assertions.

```sh
DB_URL=jdbc:postgresql://127.0.0.1:15432/funchole NATS_URL=nats://127.0.0.1:14222 \
S3_ARTIFACT_ENDPOINT=http://127.0.0.1:19000 BAO_ADDR=http://127.0.0.1:18200 \
FUNCHOLE_TEST_MCP_SMOKE=true DOCKER_HOST=unix:///var/run/docker.sock \
./gradlew :controlplane:test :controlplane:e2eTest --no-daemon --max-workers=2
```

Final backend-wide verification also passes `./gradlew test :controlplane:e2eTest` in that environment: 553 Java tests across nine result groups, zero failures and zero skipped. `npm test` in `runtime/node` passes all four Node tests. Total: 557 tests, plus the separate live deployment journey. No credentials were copied into reports and no commits or pushes were made.

## Shipping acceptance

For a STATIC app, an agent can find a tested source template, submit one multi-page Function Version, poll its build, create a STATIC Flow Version referencing the READY artifact, adopt it, and check the real HTTPS pages/assets. For a dynamic app, it can learn the actual HTTP input envelope, parse the body, attach any supplied database/configuration, test a draft Flow, adopt it, and check the real HTTP contract. A direct Invocation succeeding is not proof that DNS, TLS, routing, cookies, or browser behavior work.

For multiplayer, the plan explicitly names the available persistence and polling path and any external-service requirement. It must not silently claim native WebSocket support. When prerequisites such as a verified base domain, database credentials, DNS access, or operator-installed npm are missing, the agent asks for only that missing input rather than inventing infrastructure.
