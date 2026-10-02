# FuncHole MCP before the redesign

Research date: 2026-10-02. Sources are the repository's executable code and config. This note describes the baseline, not new functionality from this session.

## Server, authentication, and protocol

`build.gradle:11,22,55` pins Spring Boot 4.1.1, Spring AI 2.0.1, and Java 25. No Spring upgrade is needed. `controlplane/build.gradle:33-39` adds the WebMVC MCP starter. `controlplane/src/main/resources/application.yml:29-40` selects STREAMABLE at `/api/mcp` with a brief platform summary. There are no resource or prompt providers in the baseline. Tools auto-register through `@Service` and `@McpTool`.

`security/ApiKeyAuthenticationFilter.java:46-75` accepts `Authorization: Bearer fh_mcp_...`, resolves the user, and persists authentication into request attributes for async dispatch. JWT authentication remains an alternative. `config/SecurityConfig.java:64-99` requires authentication on MCP and orders API-key authentication before JWT. `mcp/CurrentMcpUser.java:21-26` resolves the current user's ID; the CRUD services perform ownership checks.

The baseline dependency graph resolves MCP SDK 2.0.0 through Spring AI's BOM. Spring AI's annotations artifact and MCP SDK 2.0.1 source were downloaded from Maven Central for API verification; the redesign overrides the SDK to that patch release. `SyncMcpToolProvider#getToolSpecifications` generates schemas and callbacks from annotated methods. `ToolInputValidator` validates arguments through the SDK JSON Schema validator. The Java SDK release notes identify 2025-11-25 as its implemented spec, not 2026-07-28:
https://github.com/modelcontextprotocol/java-sdk/releases/tag/v2.0.1

The latest specification changes the wire contract, not just the version string. It removes initialization and sessions, requires per-request metadata and mirrored HTTP headers, adds server/discover, and adds resultType and cache guidance:
- https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning
- https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http
- https://modelcontextprotocol.io/specification/2026-07-28/server/discover

## Tool inventory

Paths below are under `controlplane/src/main/java/com/funchole/backend/controlplane/mcp/`. Each group uses the services behind the corresponding REST controllers.

| Source | Baseline tools | Discovery or guidance gap |
| --- | --- | --- |
| `FunctionMcpTools.java:38-97` | list_functions, get_function, create_function, update_function, delete_function | Runtime parameter contains a tutorial on shared assets; no task index. |
| `FunctionVersionMcpTools.java:77-327` | list_function_versions, get_function_version, create_function_version, submit_function_version_source, get_function_version_source, deploy_function_version, get_function_version_build_logs, get_function_version_config, set_function_version_env_var, set_function_version_secret, list_function_version_databases, attach_function_version_database, detach_function_version_database | Long source-submission description mixes HTTP, static layouts, build process, and security. Headers guidance is stale. Clone, full replacement, async deployment, and recovery semantics are essential. |
| `FlowMcpTools.java:39-102` | list_flows, get_flow, create_flow, update_flow, delete_flow | Description conflates Flow with Flow Route. Paths accept exact, colon parameters, or trailing wildcard. |
| `FlowVersionMcpTools.java:53-224` | list_flow_versions, get_flow_version, get_flow_full_source, create_flow_version, adopt_flow_version, archive_flow_version, delete_flow_version, list_flow_steps, create_flow_step, update_flow_step, delete_flow_step | Step tutorial duplicates stale headers guidance. Adoption is a separate live-traffic mutation. |
| `GatewayMcpTools.java:39-93` | list_gateways, get_gateway, create_gateway, update_gateway, delete_gateway | Admin needs a verified AppDomain; cloud users may omit it. Hostname/TLS readiness is not a shipping checklist. |
| `DomainMcpTools.java:36-67` | list_domains, get_domain, create_domain, initiate_domain_verification | DNS changes are external prerequisites. Creating a record does not verify it. |
| `CustomDomainMcpTools.java:49-108` | attach_custom_domain, verify_custom_domain, list_custom_domains, detach_custom_domain | TXT verification, CNAME/A targets, and certificate issuance require a complete DNS/TLS journey. |
| `DatabaseMcpTools.java:39-106` | list_databases, get_database, reveal_database_password, create_database, update_database, delete_database | Registers external Postgres, does not provision it. The explicit password-reveal tool means claims that passwords can never be returned are inaccurate. |
| `EnvironmentProfileMcpTools.java:38-114` | list_environments, get_environment, create_environment, update_environment, delete_environment, get_environment_config, set_environment_env_var, set_environment_secret | No top-level route to the process.env contract or sharing strategy. |
| `FlowConfigurationMcpTools.java:27-73` | list_flow_environments, attach_flow_environment, detach_flow_environment, list_flow_databases, attach_flow_database, detach_flow_database | Database instructions redirect to another long tool description. |
| `InvocationMcpTools.java:55-143` | invoke_function_version, invoke_flow_version, get_invocation | Polling is documented, but direct payload vs real HTTP envelope deserves explicit guidance. |
| `FunctionExampleMcpTools.java:32-139` | get_function_example | Five fixture-backed scenarios already exist. NODE_BASIC description incorrectly says headers are ignored; NODE_REQUEST_HEADERS shows the actual supported headers. |

There are 71 baseline tools. `FunctionExampleFixtures.java` contains NODE_BASIC, NODE_DATABASE, NODE_ENV_VARS, NODE_REQUEST_HEADERS, and STATIC_MULTIPAGE source. Its class documentation names the integration/E2E tests that use each fixture. Examples should remain executable shared fixtures, not copied Markdown snippets.

## Platform capabilities and model corrections

- Functions can build NODE artifacts or STATIC sites. `FunctionVersionMcpTools.java:133-193` submits a complete file set. `functionbuild/runtime/staticsite/StaticRuntimeBuilder.java` installs npm dependencies then runs the package build script. The output must include an index page.
- A Flow has routing fields in the current entity/API, but its logical composition is held in versions and steps. See `FlowMcpTools.java:61-78` and `FlowVersionMcpTools.java:90-179`.
- Flow Versions are editable drafts, then immutable when adopted. `service/FlowVersionService.java:84-109` archives the previous adopted version and activates the new one. `service/FlowStepReferenceValidator.java:55-82` requires READY Function Versions and ADOPTED sub-Flows.
- STATIC routes bypass invocation and serve artifact files. `FlowVersionService.java:124-128` explains why a terminal STATIC FUNCTION step is accepted.
- NODE HTTP input has method, hostname, path, rawUri, a string body, pathParameters, header arrays, and cookies. `gateway/.../GatewayHttpHandler.java:569-582` is the authoritative shape. Direct invocations do not manufacture this envelope.
- Response body is always JSON-serialized. Optional headers are applied, including Set-Cookie arrays and Content-Type, except Content-Length and Transfer-Encoding. `GatewayHttpHandler.java:463-522`. Overriding Content-Type does not turn a JSON-quoted string into raw HTML.
- Sequential FUNCTION, MIDDLEWARE, RESPONSE and flattened SUB_FLOW steps execute. `dispatcher/.../ExecutionPlanner.java:28-41`. Metadata does not implement retries or branches. `docs/limitations.md:29-37` records remaining execution risks and lack of retry/backoff.
- Databases are external Postgres connections. Runtime context.db returns pg.Pool; see `runtime/node/executor.mjs` and NODE_DATABASE fixtures. No dedicated query/migration tool exists; migrations use a deployed one-off Function.
- No native WebSocket application execution is exposed in the inspected Gateway/Dispatcher/MCP code. HTTP polling plus shared Postgres can implement turn-based multiplayer; realtime transport requires another service or platform work.
- Builds and runtime execution are not sandboxed. `MCP_TESTING_FEEDBACK.md:502-544` and ProcessExecutor/PersistentNodeExecutor establish this boundary. Guidance is not isolation.

MCP focuses on app building blocks. Account/login/API-key lifecycle, system health/metrics, quota administration, and infrastructure provisioning are not app-authoring tools in this inventory. Hosted signup does provision a default Gateway and Database through `service/CloudSignupService.java:91-92`; that is distinct from MCP's `create_database` registration. A server should reuse existing defaults and explain missing prerequisites rather than use REST as an undocumented escape hatch.

## Every distinct feedback theme

Source: [MCP_TESTING_FEEDBACK.md](../../MCP_TESTING_FEEDBACK.md), read in full.

| Lines | Failure or question | Baseline status and redesign implication |
| --- | --- | --- |
| 69-126 | Wrong source entrypoint/handler, Lambda statusCode assumptions, wrong runtime | Descriptions already existed but did not reliably route the agent to the contract. Offer task-specific guides and examples before submission. |
| 52-65,207-245 | STATIC confusion, one Function per page, no literal file layout | Prior examples added. Keep a complete STATIC journey, including wildcard route and STATIC Flow Version. |
| 128-157 | Unknown pg.Pool API and missing migration/seed mechanism | Prior description fixes explain one-off Functions. Route database tasks to one authoritative guide. |
| 159-205,252-308 | npm missing, uncertainty about cp/mkdir, guessed npx/sh workarounds | Image fixed. Build command remains fixed; missing npm is operator work, not another source revision. |
| 310-403 | Source lost from ephemeral disk while durable artifacts kept serving | S3 source store added. Working HTTP does not prove source is rebuildable. Inspect and preserve source before updates. |
| 405-500 | Examples invisible among 60+ tools; prose may drift | Example tool and failure pointer added. Preserve shared fixture source and direct failure-to-guide routing. |
| 502-544 | Unsafe builds/runtime, no tenant isolation in process | Wording added, sandboxing still open. Do not present wording as a security control. |
| 8-50 | Actual STATIC 500, silent exception logging, stale dev image, orphaned hot-reload JVM | Logging/image fixes recorded; watcher concern separate. Isolated tests do not prove the user's live stack was updated. No unrequested stack restart. |

## Idea to shipped app, baseline journey

1. List existing Gateways, Functions, Flows and shared resources before inventing new ones. Lists are paginated; an empty page is not universal absence.
2. If a Gateway is missing, resolve domain verification and DNS prerequisites. Admin needs a verified domain; hosted users may already have a default Gateway. Creation does not prove TLS readiness.
3. Choose STATIC for browser files and NODE for JSON HTTP logic. Fetch the matching fixture. A dynamic app normally uses both, not a single wrong-runtime handler.
4. Create a Function and DRAFT Function Version. Attach supplied database/configuration as required. New versions clone latest source/config unless startEmpty is chosen.
5. Submit all source files, entrypoint and handler. A replacement submission drops omitted files.
6. Deploy and poll get_function_version to READY or FAILED. On FAILED inspect logs, create a cloned DRAFT, and correct the actual failure. A missing host binary cannot be fixed by guessing a different source command.
7. Create the Flow and its Flow Route under the Gateway, then a matching runtime Flow Version. Reference only READY Function Versions. STATIC uses a FUNCTION step; NODE finishes with RESPONSE. Reuse a deployed component across routes rather than cloning it.
8. Invoke the draft NODE Flow, then inspect the Invocation. A synthetic payload should reproduce the real HTTP envelope if testing HTTP handlers. Direct calls do not test DNS, TLS or cookies.
9. Adopt the Flow Version. This changes live traffic and archives its predecessor.
10. Test the real HTTPS URL, including pages, nested assets, parameters, status, headers, cookies, and error cases. Report checked URLs and any remaining external blockers.

The baseline has no entry tool that assembles this sequence or a queryable guide index. The redesign adds discovery and precise completion criteria around the existing executable operations.
