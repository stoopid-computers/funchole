# Convex MCP abstractions and lessons for FuncHole

Research date: 2026-10-02. Scope: redesign FuncHole's MCP interface only. This note proposes no change to FuncHole's backend, data model, public REST API, or Runtime.

## Findings that matter

1. **Convex MCP exposes 12 tools.** It has a fixed tool catalog, not a universal JavaScript dispatcher. Its useful compression is `status → functionSpec → run`, plus a separate read-only query evaluator. Four environment-variable tools remain ordinary operation-specific tools. [C1][C2][C5][C6][C7]
2. **A small MCP depends on work happening elsewhere.** Convex MCP does not create projects, edit source, deploy functions, push database schemas, or register HTTP routes. The CLI and ordinary project files do that work. Copying its tool count without accounting for those omissions would remove FuncHole's app-authoring capabilities. [C1][C19]
3. **Discovery precedes execution.** A caller selects a deployment, discovers function identifiers and validators, then supplies a function name and argument string. `run` compresses invocation, not the whole development lifecycle. [C3][C5][C6]
4. **Read-only is not the same as non-sensitive.** Convex separates production metadata inspection, production data/log reads, secret-bearing environment reads, and writes. FuncHole should preserve these distinctions even when several operations share a tool. [C4][D1]
5. **Do not copy its selector as a security boundary.** It is an unsigned Base64 encoding of a project path and deployment-selection object. The source checks a caller-supplied selection kind before resolving credentials, rather than checking the resolved deployment type in the MCP handler. This is a static-review concern, not a tested exploit. [C3][C4][C14]
6. **Keep FuncHole's on-demand guidance.** Convex's inspected MCP implementation advertises tools only. FuncHole already has bounded discovery, exact-schema lookup, guides as resources plus a tool fallback, and a project-local learning policy. A smaller executable catalog should retain those mechanisms. [C2][F2][F3][F6]

## Evidence and boundaries

The principal evidence is the sibling checkout at `/Users/samnan/Documents/codes/work/research/convex-backend`, clean when inspected, commit `3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722`. Its last commit is dated 2026-10-01. FuncHole was initially clean at commit `fade1ceb5a52133f3299127f84c9f0bb57f853e5`. Pi at `/Users/samnan/Documents/codes/work/research/pi` was clean at commit `7fbbd5f4a1d982bb02d63472dde0774fa639f99b`.

Applicable instructions read: Convex `npm-packages/AGENTS.md`, `crates/AGENTS.md`, `crates/local_backend/AGENTS.md`, and Pi `AGENTS.md`. No applicable root/ancestor FuncHole AGENTS.md was found. FuncHole's `control-plane-web/AGENTS.md` does not govern the files studied here.

Current official documentation was queried through Context7 using `/websites/convex_dev` for MCP configuration/security and public function APIs. Official live docs were also fetched directly. Source is authoritative for this checkout's implementation. Documentation claims are identified separately where they differ. No server was started, no credentials were used, and no deployments or safety bypasses were exercised.

All `C` citations below pin the Convex commit. All `F` citations pin the FuncHole commit. `P` citations pin Pi. The source index provides repository-relative paths and exact line ranges; resolve them under the checkout roots above.

## Public developer model versus actual MCP

| Public Convex abstraction | Meaning | What the MCP actually provides |
| --- | --- | --- |
| `query` | A named, read-only function with named arguments and a query context. Database reads share a consistent snapshot. Determinism allows caching and reactive client subscriptions. | `run` can invoke a deployed query once. `runOneoffQuery` can evaluate a temporary query module. Neither tool subscribes to results. [C6][C7][C15][D2] |
| `mutation` | A named function with database read/write access. Reads and writes in a mutation are atomic and isolated. | `run` invokes a deployed mutation. MCP does not offer a document CRUD writer or arbitrary one-off mutations. [C1][C6][C15] |
| `action` | A named function that can call external services or use Node.js. No direct `ctx.db`; it accesses data through `ctx.runQuery` and `ctx.runMutation`, each with its own transaction. Side effects prevent automatic retries. | `run` invokes a deployed action, with the same coarse production guard as every other function invocation. It is not a generic shell or arbitrary code execution tool. [C6][C15][D3] |
| `api` / `internal` function references | Generated function references carry function type, visibility, argument and return types. Module paths and exports determine names. Internal functions are not ordinary client-callable APIs, but administrative CLI/dashboard execution is allowed. | `functionSpec` exposes deployed metadata, including internal functions. `run` accepts string names, recognizes `api.` and `internal.` notation, and uses administrative authentication. It is not a normal application user's authorization context. [C5][C6][C12][C16][D4] |
| Database schema / `ctx.db` | Server functions use Convex's document database and its reader/writer APIs. | `tables` reads declared and inferred schema; `data` reads a table page. These are observation tools, not schema deployment tools. [C8][C9][C15] |
| Project source and deployment | Functions live in project files, commonly under `convex/`; CLI commands build, generate code, push, and deploy. | No MCP authoring/deploy tool exists in the inspected catalog. CLI `run --push`, `run --watch`, and `run --inline-query` are CLI facilities, not options on MCP `run`. [C1][C19][D2] |

The reusable lesson is an execution contract with clear effects and discoverable inputs. The document database, reactive subscription engine, generated TypeScript SDK, transactional mutation runtime, and Node action environment are backend/SDK choices. They are not prerequisites for a small FuncHole MCP.

## Exact tool inventory and configuration-dependent availability

`convexTools` contains exactly 12 entries. `tools/list` returns every enabled entry. No authentication- or deployment-dependent filtering happens in that listing. `--disable-tools` removes named tools from both listing and dispatch; unknown names fail startup. With `D` distinct valid disabled names, the advertised count is `12 - D`. Empty comma-separated entries are invalid names rather than ignored entries. [C1][C2]

Every tool except `status` requires a string `deploymentSelector` obtained from `status`.

| Tool | Other inputs the caller must know | Return contract | Source |
| --- | --- | --- | --- |
| `status` | Optional `projectDir`; the implementation requires it either in the call or server configuration. | `availableDeployments[]` with kind, selector, URL, optional dashboard URL and optional `readOnly`. | [C3] |
| `functionSpec` | No additional inputs. | Function metadata array. Named functions include identifier, function type, visibility, argument and return validators. HTTP action entries instead have method and path. | [C5][C17] |
| `run` | Required `functionName` and `args`, where `args` is a JSON-encoded string, not an object. | `result`, `logLines[]`. | [C6] |
| `runOneoffQuery` | Required JavaScript module source string `query`. | `result`, `logLines[]`. | [C7] |
| `tables` | No additional inputs. | `tables` keyed by name, with optional declared `schema` and `inferredSchema`. | [C9] |
| `data` | Required `tableName`, required `order` of `asc` or `desc`; optional string cursor and numeric limit, default 100, maximum 1000. | `page`, `isDone`, `continueCursor`. | [C8] |
| `logs` | Optional status `all/success/failure`, numeric cursor in milliseconds, `entriesLimit` up to 1000, approximate `tokensLimit` default 20000, and `jsonl` default false. | `entries` as a string, formatted text by default or JSONL; `newCursor`. One fetch, not tailing. | [C10] |
| `insights` | No additional inputs. | Typed insights, summary, dashboard URL. A fixed last-72-hours window for OCC conflicts and read-resource issues. | [C11] |
| `envList` | No additional inputs. | `variables[]` containing both names and plaintext values. | [C13] |
| `envGet` | Required name. | Value or null. | [C13] |
| `envSet` | Required name and value. | `success`. | [C13] |
| `envRemove` | Required name. | `success`. | [C13] |

Configuration changes practical availability, not just the count:

- Production flags leave all 12 declarations visible but cause particular calls to reject. Under the documented default, production permits the three metadata tools `tables`, `functionSpec`, and `insights`; the PII flag adds `data`, `logs`, and `runOneoffQuery`; the dangerous flag adds `run` and all four environment tools. `status` is the separate discovery entry. [C1][C2][C4][D1]
- `insights` remains declared but rejects local deployments and authentication using deployment or project keys. Scoped `CONVEX_DEPLOY_KEY` therefore does not produce an 11-tool schema catalog; it produces a 12-tool catalog with one unusable tool unless explicitly disabled. [C2][C11][D1]
- `--project-dir` changes the default path, not the schema or a project allowlist. Deployment flags change selection, not tool declarations. [C2][C3][D1]
- No separate mutation-only or query-only `run` declaration exists. A known read-only deployed query still goes through the mutating-class production guard. [C4][C6]

For comparison, direct annotation counting in FuncHole at the inspected commit gives 75 MCP tools: 71 existing app tools plus `get_funchole_guide`, `get_funchole_tool`, `search_funchole`, and `plan_application`. This is a source count, not a new live-server test. The 71 split into domains 4, custom domains 4, Gateways 5, Functions 5, Function Versions 13, examples 1, Flows 5, Flow Versions 11, Flow configuration 6, environments 8, databases 6, and Invocations 3. [F1][F2]

## Deployment selection: useful handle, misleading authority

### The useful part

`status({projectDir})` resolves project configuration and credentials once for discovery and returns selectors usable by subsequent tools. Other handlers decode the selector, change the process working directory, resolve credentials again, and act on the selected deployment. There is no separate session-bound selected deployment. Carrying the selector explicitly makes calls easier to compose and inspect. [C3][C4][C6]

The exact encoding is:

```text
deployment.kind + ':' + Base64(JSON.stringify({ projectDir, deployment }))
```

The source explains that the string works around MCP clients handling nested JSON objects poorly. There is no signature, expiry, issued-handle registry, or embedded credential. Decode ignores the visible prefix and validates the decoded payload with Zod. Treat this as an opaque routing convenience, not a capability token. [C4]

### Facts a caller must not guess

- `status` is not a complete deployment inventory despite its description. It returns the selected deployment and, in the default unspecified cloud case, adds the default production deployment. It does not enumerate every preview, named deployment, or other project. [C3]
- The description mentions `ownDev` and `urlWithAdminKey`, but the output's first `kind` actually comes from `deploymentSelectionWithinProjectFromOptions`. The current union includes `unspecified`, `prod`, `implicitProd`, `deploymentName`, `previewName`, and `deploymentSelector`. Reuse the returned selector instead of constructing it from the prose examples. [C3][C14]
- Live docs say `status` defaults to the current project directory. The source requires `input.projectDir ?? ctx.options.projectDir`, otherwise it errors. Supplying an absolute project directory avoids that discrepancy. [C3][D1]
- The current CLI supports `--deployment` with names, references, `dev`, `prod`, `local`, and cross-project references. Older `--preview-name` and `--deployment-name`, explicit URL/admin key, and `--env-file` remain implemented but hidden from help in parts of the shared command builder. [C18]
- `getMcpDeploymentSelection` substitutes the selector's within-project choice only when startup selection is not `existingDeployment`. With a fixed existing deployment, credential routing remains pinned to that deployment. [C4][C14]

### Safety and metadata limitations

The documented production policy is sound as a product distinction, but the implementation has two important rough edges:

1. The checked decoders reject only decoded `deployment.kind === "prod"`. Credential loading happens afterward and can resolve other kinds into a production deployment. `status` likewise sets the `readOnly` field only on records whose selection kind is `prod`. A named production deployment, `--deployment prod`, or a fixed production credential configuration deserves targeted testing. The reviewed MCP handlers do not perform a second check against resolved `credentials.deploymentFields.deploymentType`. This is evidence of a guard-placement risk, not proof of any particular exploitable configuration. [C3][C4][C6][C14][C18]
2. With `--cautiously-allow-production-pii`, `status` reports `readOnly: false` for production, although writes and environment reads still reject. Its description says false or absent means all tools can be used. A single boolean cannot represent the actual permission tiers. [C3][C4][D1]

FuncHole should borrow explicit selection but return server-resolved identity and allowed operation/effect classes. Resolve tenant ownership and the true target before applying policy. A stable resource reference reduces repeated IDs; it must not replace ownership checks. Avoid minting an apparent authority token that is merely encoded caller input.

Official docs say the server normally uses the user's global login credentials and can access all projects that account can access. `--project-dir` does not restrict that authority. A deployment-scoped `CONVEX_DEPLOY_KEY` is the documented way to pin access, with the loss of `insights`. The same docs warn that independently running CLI commands uses full credentials and is not governed by MCP's tool restrictions. Do not call shell fallback a safety-preserving substitute for a rejected MCP call. [D1]

## Execution and query contracts

### `functionSpec → run` is the strongest abstraction

`functionSpec` reads `_system/cli/modules:apiSpec`. The system function scans analyzed modules and returns deployed contracts, not source files or application tutorials. Missing validators become `any`; HTTP routes have a different entry shape. The whole catalog is returned without search, pagination, or per-function lookup. [C5][C17]

`run` accepts a function identifier, parses arguments with JSON5 followed by Convex JSON decoding, resolves common identifier formats, authenticates a `ConvexHttpClient` with the deployment admin key, and calls its internal unknown-function-type method. That client sends `/api/function`, which lets the backend execute a query, mutation, or action by name. `run` also captures function logs. [C6][C12][C20][C21]

This combines three function kinds into one invocation tool and avoids duplicating every deployed business function as an MCP tool. It works because the named functions already exist with server-side contracts and execution semantics. It does not eliminate the need to discover their arguments. Nor does administrative execution prove that public application authentication and authorization work. [C5][C6][C16][D4]

For FuncHole, the analogous opportunity is one invocation contract for an explicit Function Version or Flow Version reference, returning an Invocation reference. Keep its real asynchronous semantics. Existing FuncHole invocation tools already use the Dispatcher and require polling; do not copy Convex's synchronous `result` promise if it misrepresents that lifecycle. [F5]

### `runOneoffQuery` is narrowly powerful, not an unrestricted code tool

The caller sends a complete JavaScript module with one default query export:

```js
import { query } from "convex:/_system/repl/wrappers.js";

export default query({
  handler: async (ctx) => {
    return await ctx.db.query("messages").take(10);
  },
});
```

The MCP help allows only the built-in wrapper import, prohibits database writes and network access, and requires Convex query syntax. Its handler passes source unchanged to `runTestFunctionQuery`; it does not wrap bare expressions. The CLI's `--inline-query` path does wrap expressions and inject the preamble. Do not conflate them. [C7][C19][C22]

The shared helper posts a single `testQuery.js` bundle to `/api/run_test_function` with empty args and administrative credentials. The backend checks the `RunTestQuery` permission, requires the isolate environment, accepts only a default Convex function export, runs only queries without caching, and rejects mutations, actions, and HTTP actions. It temporarily stages module metadata in an uncommitted transaction; the source package is uploaded to object storage. Thus "read-only" means no user database writes, not literally no platform writes or cost. [C22][C23][C24]

This is a good pattern because the server supplies a small allowed language and enforces its limits. It is not evidence that FuncHole can safely expose arbitrary NODE code as a read-only inspection facility. FuncHole's own source-submission description says builds and handlers have unsandboxed host access. Keep Runtime execution separate from any proposed safe metadata query facility. [F4]

### Schemas and result envelopes are less strong than the type declarations suggest

The internal `ConvexTool` type stores input and output Zod schemas. MCP conversion publishes only name, description, and JSON input schema. It does not publish the declared output schema or effect annotations. Dispatch parses inputs, but does not parse/validate returned output against the output Zod schema. Responses are JSON-stringified into text content; errors return JSON text `{error: message}` with `isError: true`. There is no `structuredContent` in the shown dispatcher. [C1][C2]

This is a useful simplification for a small catalog, but not an ideal template for FuncHole's typed inspection, asynchronous operation references, or permission-aware clients. Keep output contracts discoverable and consistent across protocol adapters.

## Command and lifecycle compression

Convex has three separate mechanisms; calling all of them "MCP code mode" would hide the distinction:

| Mechanism | Compression it actually supplies | What remains outside it |
| --- | --- | --- |
| MCP `run` | Invoke an existing typed function of several runtime kinds through one call. | Function authoring, deploying, safe effect classification, app HTTP testing. [C6] |
| MCP `runOneoffQuery` | Express filtering, joins, summaries, and other read logic in one constrained query rather than many table page fetches. | Writes, arbitrary imports/network, reusable deployed source, reactive subscriptions. [C7][C24] |
| CLI `run --push`, `--watch`, `--inline-query` | Combine local-code push with invocation, watch a named query, or wrap a one-shot inline query. | These flags do not exist on MCP `run`; production push via `run --push` rejects and directs users to `convex deploy`. [C19] |

The MCP catalog contains no prompt handlers, resources, skill-writing commands, general code orchestrator, deployment tool, or autonomous learning loop. The server advertises only `tools` capabilities. Convex docs recommend an external plugin for some clients, but that recommendation is not evidence that those capabilities live in this MCP implementation. [C1][C2][D1]

The server also serializes all tool handlers with a mutex because they mutate global `process.cwd`. This is an implementation constraint, not a useful abstraction to copy into FuncHole's HTTP server. Most handlers nevertheless reuse existing CLI helpers instead of inventing a second backend API. That reuse is worth copying. [C2][C6][C13]

## Strengths and limits

### Worth adopting

- One discovery entry establishes an explicit target reference that subsequent calls carry. [C3]
- Deployed function contracts are discoverable independently of execution; one invocation tool can serve many real application functions. [C5][C6]
- One-off code has a narrow purpose and backend-enforced read semantics. It replaces repeated data retrieval with a bounded-language computation, not unrestricted authority. [C7][C24]
- Production data reads require a different permission from metadata reads, and secrets require stronger permission still. [C4][C13][D1]
- Logs support cursors, filtering, and budgets; insights produce diagnosis-oriented summaries rather than requiring the caller to interpret every raw event. [C10][C11]
- Handlers reuse existing configuration, credentials, CLI parsing, and backend contracts. [C6][C13][C22]

### Do not adopt blindly

- Twelve declarations do not mean twelve universally usable capabilities. Authentication, production policy, cloud/local mode, and disabled tools change actual availability. [C2][C11]
- A returned `readOnly` boolean is insufficient permission metadata, and guards must use resolved targets rather than caller-supplied target kinds. [C3][C4]
- `functionSpec` and `tables` return full inventories, while `run` and one-off query results have no handler-level output budget. A small declaration count can still consume large context. [C5][C6][C7][C9]
- `data.limit` has only a numeric maximum in the tool schema, not an explicit positive-integer constraint. Do not treat the description as stronger validation than the schema. [C8]
- Log token limiting has an implementation discrepancy. Comments promise newest-first token selection, but the token loop traverses the retained entries forward and stops at the first over-budget entry. The returned cursor is the backend cursor even when filtering or truncation discarded entries. FuncHole should expose truncation and continuation semantics explicitly rather than promise lossless pagination by implication. [C10]
- Environment reads reveal plaintext values. FuncHole already distinguishes secret references from secret values; preserve that stronger default. [C13][F4]
- The MCP has no detailed guidance system of its own. Reducing FuncHole's executable tools should not erase its guidance or discovery. [C2][F2][F3]

## What FuncHole can learn without adopting Convex

These are design candidates, not a finalized tool specification or implementation plan.

### Compress caller obligations, not just names

FuncHole currently makes the agent carry parent/version UUID pairs, cloning rules, full-source replacement, build polling, pinned step references, and adoption order. Source inspection already has a good compressed read: `get_flow_full_source` returns the dependency tree with unavailable reasons instead of making the agent walk every step. The same idea should guide write abstractions. [F4][F7]

A credible smaller MCP could group work around these contracts:

| Candidate contract | Obligation it would remove | Boundary it must retain |
| --- | --- | --- |
| Discover | Find task guidance, operation contracts, reusable components, and the authorized target. | Bounded summaries and exact contract lookup, not a full schema dump. |
| Inspect | Read a selected Function/Flow revision with dependencies, source, configuration references, or status. | Read-only metadata versus sensitive data; source bodies on demand. |
| Prepare a revision | Supply complete source/composition intent, clone base, and existing resource bindings in one validated request. | Draft-only changes; full replacement must be explicit; no hidden live release. |
| Build | Start building a selected draft and return an existing Function Version/operation reference. | Asynchronous completion, durable diagnostics, no success claim before READY. |
| Invoke | Execute a selected Function Version or Flow Version with one payload contract. | Real Invocation lifecycle; no claim of read-only execution or public HTTP coverage. |
| Observe | Read bounded build/Invocation diagnostics and completion state using returned references. | Cursor/budget/truncation contract; secret redaction. |
| Release | Adopt a tested Flow Version for explicit live routes, or stop serving it. | Live-traffic effects, authorization, exact revision and expected-current-live checks. |

The table intentionally describes semantics, not final names or an arbitrary seven-tool target. A separate guide-reading fallback may still be necessary for tool-only hosts. If putting two jobs together makes permissions or schemas unclear, split them. Inspection choices should be typed projections, not arbitrary SQL, shell, or NODE evaluation.

Preparation could orchestrate existing services behind an MCP call. It must disclose partial progress and references when some service operations fail. Convex's atomic mutation guarantee belongs to its database runtime; it cannot be promised for FuncHole's existing multi-service build-and-release sequence. Existing Function build and Flow adoption contracts already establish separate preparation/build/live states. [C15][F4][F7]

Keep FuncHole's current Function, Function Version, Flow, Flow Version, Gateway, and Invocation semantics. This research does not justify inventing Convex deployments or tables inside FuncHole, adding a new application manifest backend, changing Runtime isolation, provisioning databases, or rewriting REST. A selector/reference can be an MCP routing aid over existing identifiers and ownership services. [F8]

### Why `execute({operation, args})` is usually fake compression

Moving all 75 existing method names behind one string field leaves 75 operations to learn. The agent still needs every UUID relationship, prerequisite, effect, retry rule, and parameter schema. The client also loses useful per-tool safety hints unless it understands operation-level contracts. That is fewer declarations, not a narrower developer abstraction.

Convex does not demonstrate that approach. `run` calls user-defined deployed business functions with discoverable validators, and `runOneoffQuery` executes a constrained read language. They are not wrappers over 75 management CRUD methods. Four explicit environment tools further show that Convex has not collapsed all operations into a dispatcher. [C1][C5][C6][C7][C13]

If an advanced code-mode path is retained, constrain it to a documented typed command set with operation discovery, exact schemas, per-operation authorization/effects, budgets, and explicit release boundaries. Client-side composition can reduce round trips without making a generic evaluator the only authoring interface. Every nested invocation must still enforce its real effect class. This is a recommendation, not a capability found in Convex MCP.

### Preserve Pi-style discovery and project-local learning

Pi advertises skill names/descriptions/paths, then reads bodies on demand. Its default MCP code-mode exposure does not declare every tool to the model; scripts discover contracts. Deferred/direct exposure is a client choice. Server-side search alone cannot force every client to stop eagerly loading tool schemas. [P1][P2]

FuncHole should retain:

- A short starting pointer and task-specific guide summaries that explain when to read them.
- Native guide resources and a tool fallback reading the same Markdown.
- Bounded catalog search and exact contract lookup, updated to expose the new semantic contracts rather than an obsolete hidden CRUD list.
- User-invoked build/repair prompts as guidance, not implied authorization to deploy or release.
- Project-local runbooks or skills containing proven order, resource references, verification steps, and unresolved facts, only when the host can write files and the user permits it.
- Reviewed shared guidance; no agent-authored global policy edits, leaked credentials, or tenant-to-tenant learning authority.

These requirements are already reflected in FuncHole's discovery and guide implementation and its evolve guide. Keep them while changing the app-authoring contracts. Convex's small MCP is not a reason to remove them. [F2][F3][F6]

## Questions the redesign still needs to answer

1. What exact existing object defines an authorized selection scope? FuncHole has no need to invent a Convex-like project/deployment data model just to reduce repeated arguments.
2. How does a prepare call report validation errors, partial progress, cloned source, and full replacement? What happens after a FAILED build?
3. Which existing resource configuration jobs can be folded into draft preparation without implicitly revealing secrets, creating infrastructure, or changing live traffic?
4. Which actions are metadata-only, data-sensitive, secret-sensitive, host-code-executing, or live-traffic-mutating? Can clients discover this before calling?
5. How are parent/version ownership and the expected current live revision rechecked when following an opaque reference?
6. Which outputs have budgets and resumable cursors? What is the behavior after an uncertain timeout or retry?
7. Can an app be authored, tested, released, inspected, and repaired through the smaller MCP without an undocumented REST or shell escape hatch?

A smaller tool count is useful only if those answers become easier for the caller.

## Source index

All source links are pinned, not branch URLs. The local paths below provide the same evidence without network access.

| Citation | Path and lines | What it establishes |
| --- | --- | --- |
| [C1] | `npm-packages/convex/src/cli/lib/mcp/tools/index.ts:15-49` | Tool type, published schema fields, all 12 tools. |
| [C2] | `npm-packages/convex/src/cli/mcp.ts:27-196` | Startup flags, disabling, tool-only capabilities, auth, serialization, results/errors, listing. |
| [C3] | `npm-packages/convex/src/cli/lib/mcp/tools/status.ts:16-145` | Project-dir requirement, selector discovery, actual deployment subset, readOnly behavior. |
| [C4] | `npm-packages/convex/src/cli/lib/mcp/requestContext.ts:74-183` | Production tiers, encoding/decoding, selection substitution. |
| [C5] | `npm-packages/convex/src/cli/lib/mcp/tools/functionSpec.ts:8-60` | Metadata contract and system-query call. |
| [C6] | `npm-packages/convex/src/cli/lib/mcp/tools/run.ts:10-84` | Invocation inputs, coarse guard, admin auth, logs/results. |
| [C7] | `npm-packages/convex/src/cli/lib/mcp/tools/runOneoffQuery.ts:11-101` | Module syntax, import restrictions, read-only description, source pass-through. |
| [C8] | `npm-packages/convex/src/cli/lib/mcp/tools/data.ts:8-74` | Required order, pagination, max-only limit validation. |
| [C9] | `npm-packages/convex/src/cli/lib/mcp/tools/tables.ts:8-92` | Declared/inferred schemas and unpaged table map. |
| [C10] | `npm-packages/convex/src/cli/lib/mcp/tools/logs.ts:9-189` | Log formats, filters, budgets, cursor and limiter implementation. |
| [C11] | `npm-packages/convex/src/cli/lib/mcp/tools/insights.ts:92-202` | Health report and cloud/user-only restrictions. |
| [C12] | `npm-packages/convex/src/cli/lib/run.ts:166-260` | JSON5/Convex argument parsing and name normalization. |
| [C13] | `npm-packages/convex/src/cli/lib/mcp/tools/env.ts:8-198` | Four env tools, plaintext reads, guard and CLI-helper reuse. |
| [C14] | `npm-packages/convex/src/cli/lib/api.ts:97-153,847-938` | Selection union/options and resolved credential handling. |
| [C15] | `npm-packages/convex/src/server/registration.ts:43-166,184-225,284-396` | Mutation/query/action contexts and transaction boundaries. |
| [C16] | `npm-packages/convex/src/server/api.ts:14-101` | Public function types and typed function references. |
| [C17] | `npm-packages/system-udfs/convex/_system/cli/modules.ts:5-59` | FunctionSpec contents, any-validator fallback, HTTP-route variant. |
| [C18] | `npm-packages/convex/src/cli/lib/command.ts:168-235` | Current --deployment syntax, hidden older/config options. |
| [C19] | `npm-packages/convex/src/cli/run.ts:26-124,140-215` | CLI-only push/watch/inline-query, production-push rejection. |
| [C20] | `npm-packages/convex/src/browser/http_client.ts:536-605` | Internal unknown-type execution method and wire body. |
| [C21] | `crates/local_backend/src/public_api.rs:207-265` | Authenticated /function backend routing to execute_any_function. |
| [C22] | `npm-packages/convex/src/cli/lib/runTestFunction.ts:4-118` | Module wrapping differs from MCP; shared test-function request. |
| [C23] | `crates/local_backend/src/dashboard.rs:288-337` | Admin authentication and RunTestQuery permission check. |
| [C24] | `crates/application/src/lib.rs:2697-2868` | Isolate/default-export restrictions, query-only execution, object-store upload. |
| [F1] | `docs/mcp-redesign-plan.md:85-97` | Existing 71 plus 4 tools and prior deployed 75-tool verification. Annotation count independently checked here. |
| [F2] | `controlplane/src/main/java/com/funchole/backend/controlplane/mcp/McpDiscoveryTools.java:22-82` | Guide fallback, exact-schema lookup, bounded search. |
| [F3] | `controlplane/src/main/java/com/funchole/backend/controlplane/mcp/McpGuideCatalog.java:15-53` | Eight task-specific guides and same-content native resources. |
| [F4] | `controlplane/src/main/java/com/funchole/backend/controlplane/mcp/FunctionVersionMcpTools.java:99-246` | Clone/full-replace/build lifecycle, unsandboxed code warning, secret references. |
| [F5] | `controlplane/src/main/java/com/funchole/backend/controlplane/mcp/InvocationMcpTools.java:55-99` | Async Function/Flow invocation and durable inspection. |
| [F6] | `controlplane/src/main/resources/mcp/guides/evolve.md:1-23` | Component reuse, pinned revisions, project-local learning and reviewed shared guidance. |
| [F7] | `controlplane/src/main/java/com/funchole/backend/controlplane/mcp/FlowVersionMcpTools.java:75-159` | Dependency-tree read, draft composition, adoption/live traffic, pinned component versions. |
| [F8] | `CONTEXT.md:1-66` | FuncHole's existing domain language and plane boundaries. |
| [P1] | `packages/coding-agent/docs/skills.md:3-65` | Skill discovery, on-demand bodies, project-local skills and trust. |
| [P2] | `packages/coding-agent/docs/mcp.md:167-230` | Code-mode/deferred/direct exposure, discovery, resources and safety annotations. |

Official live documentation, retrieved 2026-10-02:

- [D1]: MCP tool inventory, project-directory warning, production permission matrix, scoped keys and external-plugin recommendation. Context7 corroborated the current production matrix and deploy-key restriction.
- [D2]: Query names, validators, deterministic read semantics, caching/reactivity and limits. Context7 also returned public query/mutation examples.
- [D3]: Action execution, indirect database access, transaction boundaries, side effects and retry behavior. Context7 corroborated `runQuery`/`runMutation` and reactive `useQuery` distinctions.
- [D4]: Internal functions versus administrative CLI/dashboard execution.

[C1]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/tools/index.ts#L15-L49
[C2]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/mcp.ts#L27-L196
[C3]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/tools/status.ts#L16-L145
[C4]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/requestContext.ts#L74-L183
[C5]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/tools/functionSpec.ts#L8-L60
[C6]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/tools/run.ts#L10-L84
[C7]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/tools/runOneoffQuery.ts#L11-L101
[C8]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/tools/data.ts#L8-L74
[C9]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/tools/tables.ts#L8-L92
[C10]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/tools/logs.ts#L9-L189
[C11]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/tools/insights.ts#L92-L202
[C12]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/run.ts#L166-L260
[C13]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/mcp/tools/env.ts#L8-L198
[C14]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/api.ts#L97-L153
[C15]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/server/registration.ts#L43-L396
[C16]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/server/api.ts#L14-L101
[C17]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/system-udfs/convex/_system/cli/modules.ts#L5-L59
[C18]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/command.ts#L168-L235
[C19]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/run.ts#L26-L215
[C20]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/browser/http_client.ts#L536-L605
[C21]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/crates/local_backend/src/public_api.rs#L207-L265
[C22]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/npm-packages/convex/src/cli/lib/runTestFunction.ts#L4-L118
[C23]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/crates/local_backend/src/dashboard.rs#L288-L337
[C24]: https://github.com/get-convex/convex-backend/blob/3b1a890a04ffaf7bc0b2905f6d39bc7c4fac4722/crates/application/src/lib.rs#L2697-L2868
[F1]: https://github.com/func-hole/funchole/blob/fade1ceb5a52133f3299127f84c9f0bb57f853e5/docs/mcp-redesign-plan.md#L85-L97
[F2]: https://github.com/func-hole/funchole/blob/fade1ceb5a52133f3299127f84c9f0bb57f853e5/controlplane/src/main/java/com/funchole/backend/controlplane/mcp/McpDiscoveryTools.java#L22-L82
[F3]: https://github.com/func-hole/funchole/blob/fade1ceb5a52133f3299127f84c9f0bb57f853e5/controlplane/src/main/java/com/funchole/backend/controlplane/mcp/McpGuideCatalog.java#L15-L53
[F4]: https://github.com/func-hole/funchole/blob/fade1ceb5a52133f3299127f84c9f0bb57f853e5/controlplane/src/main/java/com/funchole/backend/controlplane/mcp/FunctionVersionMcpTools.java#L99-L246
[F5]: https://github.com/func-hole/funchole/blob/fade1ceb5a52133f3299127f84c9f0bb57f853e5/controlplane/src/main/java/com/funchole/backend/controlplane/mcp/InvocationMcpTools.java#L55-L99
[F6]: https://github.com/func-hole/funchole/blob/fade1ceb5a52133f3299127f84c9f0bb57f853e5/controlplane/src/main/resources/mcp/guides/evolve.md#L1-L23
[F7]: https://github.com/func-hole/funchole/blob/fade1ceb5a52133f3299127f84c9f0bb57f853e5/controlplane/src/main/java/com/funchole/backend/controlplane/mcp/FlowVersionMcpTools.java#L75-L159
[F8]: https://github.com/func-hole/funchole/blob/fade1ceb5a52133f3299127f84c9f0bb57f853e5/CONTEXT.md#L1-L66
[P1]: https://github.com/earendil-works/pi/blob/7fbbd5f4a1d982bb02d63472dde0774fa639f99b/packages/coding-agent/docs/skills.md#L3-L65
[P2]: https://github.com/earendil-works/pi/blob/7fbbd5f4a1d982bb02d63472dde0774fa639f99b/packages/coding-agent/docs/mcp.md#L167-L230
[D1]: https://docs.convex.dev/ai/convex-mcp-server
[D2]: https://docs.convex.dev/functions/query-functions
[D3]: https://docs.convex.dev/functions/actions
[D4]: https://docs.convex.dev/functions/internal-functions
