# MCP primitives redesign

Status: implemented and backend-tested. Live replacement deployment and fresh-context procedure reuse remain unverified. The user approved replacing old MCP tool names and excluding database-password revelation on 2026-10-02. This replaces PR #18's earlier 75-tool catalog. The [previous plan](mcp-redesign-plan.md) records that superseded implementation, not acceptance evidence for this replacement.

## Goal and scope

An agent should learn a few operations that build, inspect, test and publish Functions and Flows. It should not reproduce the Control Plane's REST call sequence. Preserve on-demand discovery, task guidance, component reuse and project-local learning.

Changes belong to the MCP module, its registration, guidance, tests and verification clients. Keep the domain entities, REST controllers, Runtime, builders, Dispatcher and Gateway behavior unchanged. Keep the four currently supported protocol revisions, authentication, ownership checks, body limits and per-user call budget. Tool-name compatibility is a separate decision from protocol compatibility.

## Evidence to read

- [FuncHole interface assessment](research/2026-10-02-funchole-mcp-primitives-assessment.md), including workflow counts and live-state hazards.
- [Pi discovery and evolution analysis](research/2026-10-02-pi-self-discovery-analysis.md), with the current-source checks in the assessment.
- [Convex abstraction and MCP research](research/2026-10-02-convex-mcp-abstractions.md), distinguishing the developer model from the actual 12-tool MCP.

Convex's useful pattern is target selection, contract discovery and invocation of named functions. Its MCP does not author or deploy source; those jobs use project files and the CLI. FuncHole must compress its existing authoring lifecycle rather than copy that omission. Convex's query/mutation/action distinction also makes effects part of the contract. FuncHole can preserve clear metadata reads, code execution and live-traffic changes without adopting Convex's database or runtime.

## Design rules

1. Each mutation does one domain-level job. Build source, compose a draft, invoke code, or change live traffic. Avoid a generic operation-name dispatcher.
2. Keep preparation separate from adoption. A build producing a READY Artifact does not publish a Flow Route.
3. Give one owned target reference back to the caller. Resolve parent IDs inside the MCP module and validate ownership on every read and mutation.
4. Hide bookkeeping, not effects. Complete source replacement, live configuration changes, external DNS prerequisites and unsandboxed execution remain explicit.
5. Return durable references, bounded observations, guide pointers and safe next actions. The caller can continue after a timeout or reconnect without a server-side conversation token.
6. Reuse existing domain services. No arbitrary JavaScript/SQL execution tool, server-side agent loop, invented database provisioning, or new app/release persistence model.

## Implemented operations

The registered catalog contains 11 tools. Six cover the build loop; the remaining tools handle configuration, supplied infrastructure and cleanup. The user approved omitting password revelation from the redesigned MCP.

| Tool | Job and important limit |
| --- | --- |
| `discover` | Find task guides, tested examples, exact contracts and owned components. Return bounded summaries and pointers, with explicit continuation. Separate knowledge and owned-state search scopes. |
| `read` | Read one owned target or a known guidance URI. State is the default view; source manifests, individual files, dependency trees, configuration keys, build logs and Invocation detail load only when requested. Secret values are excluded. |
| `build_function` | Create a new Function Version from source and resource bindings, and start its asynchronous build. For an existing Function, identify the base revision explicitly. Return the durable revision and status pointer. This does not adopt any Flow. |
| `compose_flow` | Create a complete draft Flow Version from pinned components. Infer positions from the ordered list and provide a one-component shorthand with the correct STATIC/NODE terminal step. Leave an existing live Flow Route and shared bindings unchanged. |
| `invoke` | Invoke a READY Function Version or executable Flow Version and return an Invocation reference. Execution may change application data. STATIC file serving remains a Gateway check. |
| `publish_flow` | Adopt one exact draft Flow Version for live traffic. Identify the intended prior live revision and reject a known stale request. Any route-setting change must be explicit and cannot silently occur in `compose_flow`. No multi-Flow atomic release promise. |
| `configure` | Apply a bounded configuration patch to an EnvironmentProfile or explicit shared Flow bindings. Function-Version configuration belongs in `build_function`. Existing shared values and bindings can affect live traffic. No platform-wide CRUD commands. |
| `connect_database` | Register a supplied Postgres connection or explicitly update an owned connection. It does not provision a database or return its password. Updating a shared connection can affect live traffic. |
| `configure_gateway` | Create or explicitly change an owned Gateway with the platform's existing verified-domain prerequisites. Reuse hosted defaults where present. Return hostname and certificate observations, not a claim that external HTTPS works. |
| `claim_domain` | Register or re-check one DNS ownership claim, for an admin base domain or a Gateway custom domain. Return the exact external DNS requirements and current certificate state. It does not write DNS records. |
| `retire` | Explicitly retire one owned resource or Flow Version using only supported archive, detach or deletion behavior. Require the typed disposition, report affected live use where knowable, and do not cascade into unrelated resources. Step removal is part of composing a replacement. |

Exact input/output schemas are generated and registered in `McpGuidanceConfig`, and read on demand through `funchole://tools/{name}`. `configure` is limited to profile entries and Flow bindings. `retire` accepts one reference and one typed disposition, not a batch-command list.

### Example build loop

For a new STATIC site using an existing Gateway:

1. Discover the Gateway and read the STATIC guide and tested fixture.
2. `build_function` accepts the complete multi-page source and returns a PUBLISHING Function Version reference.
3. `read` that reference until READY, or follow its diagnostic pointer on FAILED.
4. `compose_flow` creates the draft composition under the intended Flow Route using that READY reference.
5. `publish_flow` adopts the exact draft, with an explicit expectation about the current live revision.
6. The host checks HTTPS pages and assets, then records verified references and checks in a permitted project-local runbook.

The candidate uses three mutation contracts instead of the current eight, plus explicit status reads. A NODE API adds `invoke` and an Invocation read before adoption. External DNS, TLS and browser checks remain real checks, not simulated success inside the deployment tool.

### Read references

Use server-generated references whose target type and ownership can be validated. Preserve guide URIs such as `funchole://guides/start`. Add exact-contract and fixture pointers without changing the tested source fixtures. Source reads first return the file manifest, then fetch selected files. Lists and logs carry continuation instead of an unbounded dump.

Native resources and `read` must use the same content provider. Both protocol adapters must use the same registered operation catalog and resource resolver. The current modern adapter reads only the guide catalog, so expanding resources requires updating both paths, not just adding a tool.

Owned inventory must use bounded, ownership-scoped queries. When a service offers paginated listing but no text search, return that listing with continuation. Do not filter one page and claim it proves that no matching component exists anywhere.

### Build contract

- A new Function needs a caller-chosen stable key, runtime and source. An existing Function uses its returned reference and an explicit base Function Version.
- Prefer a complete source bundle initially. Omission removes files from the replacement revision. File-edit conveniences can be added only with an exact base and content-conflict checks.
- Inline ordinary env values, write-only secrets and Database references in the build request. Validate ownership and shape before creating state wherever possible.
- Return PUBLISHING without waiting minutes in the HTTP request. `read` observes READY/FAILED and retrieves bounded logs.
- A failure after a durable draft or external write reports the surviving references. No automatic blind retry and no claim that Postgres, S3 and OpenBao form one transaction.
- A changed Function revision does not change other Flows pinned to an older revision.

### Compose and publish contracts

- Express a complete composition instead of step CRUD. Resolve referenced parent IDs on the server; preserve explicit step roles for advanced composition.
- A single NODE HTTP handler defaults to RESPONSE. A single STATIC Artifact defaults to FUNCTION. Advanced composition still supports existing FUNCTION, MIDDLEWARE, RESPONSE and SUB_FLOW behavior.
- A new Flow needs its Gateway and Flow Route. Creating an unadopted identity must not overwrite an existing Flow or activate traffic.
- Preparing a replacement for an existing Flow may create a draft and steps, but does not change its Flow Route or shared attachments.
- Initially reject attempts to stage shared bindings in a Flow Version. That state is not versioned in the current domain model. Use explicit configuration changes with disclosed live effects, or version-local Function bindings where appropriate.
- Explicit route edits belong to the guarded `publish_flow` transaction. Postgres-backed tests prove draft isolation, stale-expectation rejection and adoption rollback if route validation fails.
- Adoption remains separate from test Invocation and external HTTP verification. Return what the Control Plane adopted and what remains unverified.

### Configuration contracts

Use typed configuration entries and explicit binding additions/removals. Omitted configuration is unchanged. Never interpret an omitted Database or secret as a removal. Required list ordering and precedence must be visible in the contract.

Existing profile and Database changes can affect any attached Flow. Require an explicit live-impact request for those changes and report the references that can be identified. A request flag makes intent explicit but does not replace host permission checks or server authorization.

### Capability migration

This table accounts for all 75 current tool names. It records the candidate replacement, including capabilities deliberately not carried into the build-focused interface. These omissions require review before tool registration changes.

| Current tool group | Candidate replacement |
| --- | --- |
| `list_functions`, `list_flows`, `list_gateways`, `list_domains`, `list_custom_domains`, `list_databases`, `list_environments` | `discover` owned inventory, with explicit scope and continuation. |
| `list_function_versions`, `list_flow_versions` | `discover` under an owned parent reference. |
| `get_function`, `get_flow`, `get_gateway`, `get_domain`, `get_database`, `get_environment`, `get_function_version`, `get_flow_version` | `read` one owned reference. |
| `get_function_version_source`, `get_flow_full_source` | `read` source manifest/files or dependency-tree view. |
| `get_function_version_build_logs`, `get_invocation` | `read` bounded diagnostics or Invocation detail. |
| `get_function_version_config`, `get_environment_config`, `list_function_version_databases`, `list_flow_databases`, `list_flow_environments` | `read` configuration/binding view, with secret values excluded. |
| `create_function`, `create_function_version`, `submit_function_version_source`, `deploy_function_version` | `build_function` owned identity, exact-base draft, complete source and asynchronous build. Standalone empty drafts are not needed for the new build loop. |
| `set_function_version_env_var`, `set_function_version_secret`, `attach_function_version_database`, `detach_function_version_database` | Explicit revision configuration in `build_function`. Updating configuration creates a new revision instead of silently editing a READY one. |
| `create_flow`, `create_flow_version`, `create_flow_step`, `update_flow_step`, `delete_flow_step` | `compose_flow` complete ordered composition. Existing live Flow Route changes are not part of preparation. |
| `list_flow_steps` | `read` the composition view. |
| `adopt_flow_version` | `publish_flow` exact draft. |
| `archive_flow_version`, `delete_flow_version` | `retire` with an explicit supported disposition. |
| `invoke_function_version`, `invoke_flow_version` | `invoke` typed revision target. |
| `create_environment`, `update_environment`, `set_environment_env_var`, `set_environment_secret`, `attach_flow_environment`, `detach_flow_environment`, `attach_flow_database`, `detach_flow_database` | `configure` typed profile patch or explicit shared binding changes. |
| `create_database`, `update_database` | `connect_database` supplied connection or explicit owned update. |
| `create_gateway`, `update_gateway` | `configure_gateway` explicit Gateway configuration, including live effects of changes. |
| `create_domain`, `initiate_domain_verification`, `attach_custom_domain`, `verify_custom_domain` | `claim_domain` typed base/custom claim and external DNS checks. Existing role and ownership checks remain. |
| `delete_function`, `delete_flow`, `delete_gateway`, `delete_database`, `delete_environment`, `detach_custom_domain` | `retire` one explicit target. No inferred cascade. |
| `get_funchole_guide`, `get_funchole_tool`, `get_function_example` | `read` guide, generated exact contract or tested fixture URI. |
| `search_funchole` | `discover` bounded knowledge search. |
| `plan_application` | On-demand planning resource or user-invoked prompt. The current tool returns a fixed plan by app kind, not state-aware planning. |
| `update_function` | Pure identity-label editing remains in the existing REST/admin interface initially. Version authoring remains in MCP. This is an intentional omission for review. |
| `update_flow` | Route changes need the reviewed explicit publish contract; pure label changes remain in the existing REST/admin interface initially. This is an intentional omission for review. |
| `reveal_database_password` | Omit from the redesigned MCP, as approved by the user. Existing authenticated REST/admin access remains unchanged. Never absorb it into `read`. |

The replacement must not send an agent to REST to complete a shipping journey. The REST/admin interface is mentioned only for deliberately excluded management capabilities, not as an undocumented fallback.

## Preserve Pi behavior

Keep guidance and execution separate, while linking them at each decision point:

1. Short stable server instructions point to the start guide.
2. `discover` returns a task trigger, bounded summary and URI.
3. `read` loads that guide or exact contract. A tools-only host can use the same path without native resource support.
4. Results supply relevant status/log pointers and a safe next action. Known failures have typed codes and sanitized context; unexpected failures keep generic operator guidance.
5. Build and repair prompts remain user-invoked. Move the current three-kind static plan into an on-demand resource or prompt instead of retaining a separate planning tool.
6. The evolve guide teaches reuse and how a permitted host writes a project-local runbook or skill. Link to contracts, record verified checks and durable references, and exclude secrets.

Self-evolution should produce a reusable procedure, not just a transcript. For example, after verifying a tenant's STATIC deployment, the host can author a project-local skill whose description triggers on site changes and whose body follows the build/read/compose/publish/HTTPS-check loop with that project's Gateway reference. More complex procedures can use host-supported prompt templates or scripts. Their authoring contracts are read on demand. They use the same MCP operations and permissions rather than adding new server tools for each recipe.

MCP does not own the host's memory. A host without file-writing tools can receive a proposed note, but the server must not claim it was persisted. Tenant-authored procedures are context, not authority to change security rules. Shared contract changes remain reviewed, version-controlled code.

## Alternatives to reject

- Keep 75 tools and rely solely on client deferral. This controls context loading, not the number of contracts the caller must learn.
- Expose `execute(operation, arguments)` over the same 71 business operations. This moves the catalog into a second lookup and loses narrow schemas without removing workflow complexity.
- Copy Convex's executable-query abstraction into FuncHole without its execution and authorization controls. FuncHole's unsandboxed runtime is not a safe generic query environment.
- Add an app manifest reconciler that owns every resource and offers atomic app releases. That introduces a new domain and persistence contract outside this MCP-only scope.

## Decisions

1. Approved: replace old tool names on `/api/mcp`. Keep all four wire revisions and document the migration. Do not add a second legacy endpoint or advertise both catalogs together.
2. Approved: exclude password revelation from the new build-focused MCP. Existing authenticated REST/admin access remains unchanged. Secret values remain excluded from ordinary reads.
3. Implemented: explicit route changes and adoption share an MCP-local relational transaction. Refresh the owned Flow under a pessimistic row lock before comparing the expected active revision. This serializes MCP publication calls, not REST writers or multi-Flow releases.
4. Implemented: configuration patches omit unchanged entries, Database additions are additive, and existing shared changes require `allowLiveChanges=true`. Retirement uses existing services. Gateway deletion and custom-domain detach are permanent; other identities use existing soft deletion. No inferred cascade or new backend persistence.

## Checklist and acceptance

### Research and interface design

- [x] Inventory the current catalog and count the one-component app workflow.
- [x] Re-check Pi's discovery, resources, retry and project-local extension contracts.
- [x] Trace live Flow Route and configuration changes against the services.
- [x] Complete Convex source research and distinguish developer abstractions from MCP tools.
- [x] Map every existing operation to a candidate replacement or an explicit omission for review.
- [ ] Review and accept that capability map, including label editing and secret revelation.
- [x] Write exact input/output schemas and compare serialized declaration size with the 75-tool baseline.
- [x] Resolve legacy names and explicit password-reveal behavior with the user.
- [x] Resolve route edits, concurrency behavior, configuration shape and retirement semantics.

### Implement only the MCP module

- [x] Add workflow modules calling ownership-checked existing services.
- [x] Use one registered catalog for all supported wire revisions. Remove old tool registration from the redesigned endpoint.
- [x] Implement shared resource resolution, bounded discovery and continuation for state, source and diagnostics.
- [x] Return safe typed outcomes for known failures and surviving state after partial writes.
- [x] Migrate all public guide/prompt references and verification scripts to the replacement contracts.
- [x] Add a catalog test that fails if old CRUD tools leak back into registration.

### Verify the replacement

- [ ] Run STATIC, dynamic and multiplayer journeys using only the redesigned endpoint.
- [ ] Verify fix-forward updates from the live base, source/config preservation, reuse across two Flows, and advanced composition.
- [ ] Test ownership at every target and referenced component, including resource URIs and per-request identity across both protocol eras.
- [ ] Test malformed inputs, source-size limits, paging bounds, stale references, timeout recovery and partial failures.
- [x] Prove that preparation does not mutate existing live Flow Routes or shared bindings, using real Postgres tests.
- [ ] Verify admin domain setup, custom domains, supplied Database connections and configuration precedence.
- [x] Verify native-resource/tool content parity and tools-only build/repair discovery in the real HTTP fixture.
- [ ] Use a permitted project-local runbook in a fresh agent context, and record what the MCP supplied versus what the host persisted.
- [ ] Re-run all existing backend/Node tests and four-version compatibility checks in the isolated VM.
- [x] Compare declaration bytes, required parameters, mutation counts and learned lifecycle rules for representative tasks.

A proposed replacement is acceptable only if the agent completes those journeys without old-tool or undocumented REST fallbacks. Existing tests passing for PR #18 do not establish this.

## Replacement verification record

2026-10-02:

- The isolated Alpine VM's complete `./gradlew test :controlplane:e2eTest` run passed 592 Java tests, zero failures/errors/skips, including all four E2E tests. Four `runtime/node` tests passed. The first attempt failed because the VM services had stopped; it is not counted as a passing run.
- Five new real-Postgres tests cover unchanged live state during preparation, stale publication without writes, actual route/adoption transaction rollback, cross-tenant reference denial and advanced/subflow component reuse.
- Focused mock tests cover complete build orchestration, explicit FAILED bases, unsafe/malformed input rejection before writes, partial-failure references, omission preservation, password exclusion, configuration guards and retirement semantics.
- After the deployment was interrupted and the VM deleted, all 52 focused tests passed again on macOS, offline, without Docker or OrbStack. This final run checks all four real HTTP protocol paths, nullable receipt validation, hyphenated discovery scopes, generated schemas, metadata pagination, native-resource/tool parity, prompts and typed error receipts.
- The old deployed 75-tool catalog measured 51,162 bytes of compact UTF-8 JSON and 144 required top-level parameters. The final 11-tool declarations measure 17,056 bytes, a 66.7% reduction, and 12 required top-level parameters. Nested request records mean top-level parameter counts alone are not a fair complexity measure.
- A new one-component STATIC site needs three mutation contracts rather than eight: `build_function`, `compose_flow`, `publish_flow`. Its meaningful inputs still include identity, source/runtime, Gateway/route, pinned component and an explicit active-version expectation. The domain lifecycle is not erased: full-file replacement, asynchronous READY/FAILED builds, draft composition and guarded live adoption remain separate rules. NODE additionally needs `invoke` and Invocation inspection.
- The deployment-image build was interrupted with only 266 MB free on the host. No live 11-tool HTTPS journey completed. Original 75-tool HTTPS/session/persistence evidence does not validate this replacement.
- At the user's request, the disposable `funchole-test` VM was deleted, including its credentials, test databases, images and build outputs. No other machine or user source files were removed.

Remaining acceptance checks are deliberately unchecked above. In particular, the expanded mutating verification script, custom-domain live setup and a fresh agent using a persisted project-local procedure require a future authorized test deployment with enough disk space. Do not present the current backend tests as that evidence.

## PR 18 review follow-up

2026-10-03:

- Build, compose, and configure now call transport-neutral application use cases with explicit user IDs. Flow publication owns its row lock, expected-active comparison, adoption, and route update below MCP. Guarded REST publication and the web UI use the same service. Legacy unconditional REST adoption also takes the shared lock.
- Initial MCP publication uses a required nullable expected reference. The string `"none"` is rejected. All nine mutating tools accept tenant/tool-scoped `clientOperationId`; durable reservations replay completed receipts and reject changed or uncertain retries.
- Knowledge discovery has curated vocabulary tags. Receipts expose exact continuation arguments and structured next actions. Eleven describes the current catalog, not a limit on future intent boundaries.
- The complete backend suite passed 595 tests with zero failures, errors, or skips. All five E2E tests passed separately, and four Node tests passed. Web route generation, TypeScript checking, focused ESLint, Python client syntax, and diff checks passed.
- The new golden test covers API-key connection/discovery, build to READY, composition, draft invocation, publication, real HTTPS, changed source, exact-base rebuild, and republishing. Its repeated build request with the same operation ID returns one revision. Application operations traverse authenticated MCP request handling through MockMvc; the Gateway, Dispatcher, Node worker, Postgres, NATS, and S3 storage are real. HTTPS pins the fixture certificate and verifies the hostname.
- This run used task-specific native containers and processes, not a recreated deployment VM. It does not prove public DNS/ACME, deployed STATIC/cookie-session persistence, custom-domain setup, or a fresh agent reusing a project-local runbook. Distributed quota accounting, source/blob references, publication revisions, and enforced multi-tenant execution boundaries remain separate platform work documented in `docs/limitations.md`.
