# PRD: Sandboxed Execution for Untrusted Code

Status: Draft for review · Owner: Platform · Created: 2026-10-08
Related: `docs/architecture.md`, `docs/limitations.md` (note: `CURRENT_PROJECT_STATE.md` predates cloud mode and is stale, see T0.1)

---

## 1. Problem

FuncHole runs code written by users and their coding agents. Today that code is **not isolated** from the platform or from other tenants. Verified on 2026-10-08 (code review plus read-only checks on the production runtime container; nothing was exploited):

| # | Finding | Evidence |
|---|---|---|
| F1 | User code is `import()`ed into one shared, long-lived Node process with full Node access (fs, child_process, net, env) | `runtime/node/executor.mjs` |
| F2 | That process inherits the container environment, including `S3_ARTIFACT_ACCESS_KEY` / `S3_ARTIFACT_SECRET_KEY` (can read/replace every tenant's artifacts) | `PersistentNodeExecutor.start` uses a bare `ProcessBuilder`; env var names confirmed on prod |
| F3 | All tenants share that one process (a function can hook `console`, `pg`, globals to capture later tenants' secrets) | `executor.mjs` is process-global by its own comment |
| F4 | Runtime container is root, no memory/CPU/PID limits, no cap drops, writable root | `docker inspect` on prod |
| F5 | Runtime is on the same Docker network as the control-plane DB, tenant DB, NATS, OpenBao, controlplane, rustfs, and has open internet egress; NATS has no auth configured | TCP connect checks from the runtime container |
| F6 | **Builds run inside the controlplane container**: `npm ci/install/run build` on user dependencies, no `--ignore-scripts`. A malicious `postinstall` runs beside the DB password, OpenBao token, S3 keys and JWT secret | `NodeRuntimeBuilder`, `StaticRuntimeBuilder`, `DefaultProcessExecutor` |
| F7 | User-controlled pages are served from `*.funchole.dev` with no abuse controls (phishing risks the brand domain) | gateway + DNS design |

No incident has occurred. This PRD removes the class of problem structurally.

## 2. Goals and non-goals

**Goals**
- G1. Untrusted code (runtime and builds) never runs where a platform credential exists.
- G2. No network path from untrusted code to any platform service.
- G3. Kernel/process-level isolation between tenants.
- G4. Hard resource limits and ephemeral sandboxes.
- G5. A way to stop an abusive tenant and to keep phishing off the brand domain.
- G6. **Every feature that works today keeps working, unchanged to the user** (section 3).
- G7. Portable: moving the untrusted zone to a bigger server is a configuration change.

**Non-goals (this PRD):** new product features, billing, multi-region, Python/Go runtimes, a rewrite of the gateway/dispatcher/controlplane APIs.

## 3. Non-regression contract ("as it is")

The rule: **no user-visible behaviour changes** except the explicit list in 3.2. Enforced by process, not goodwill:

1. **Single seam.** Isolation is introduced behind the existing `NodeExecutor` interface (`execute(request, onLog)`). The dispatcher↔runtime IPC protocol, gateway, controlplane REST/MCP APIs, database schema and UI are not changed (only additive fields, T2.5).
2. **Feature flag.** `RUNTIME_ISOLATION=legacy|sandbox` (default `legacy`), with a per-tenant allow-list for canary. Rollback = flip the flag and restart one service.
3. **Baseline first.** Epic E0 captures current behaviour (tests, golden outputs, latency) *before* any change. Nothing in E2+ merges unless the baseline suite passes in both modes.
4. **Dual-mode CI.** The same end-to-end suite runs with `legacy` and `sandbox`; outputs must match (status, headers, body, error codes, log lines, redaction).

### 3.1 Capabilities that must keep working (each mapped to a guard)

| Capability | Guard test / check |
|---|---|
| Create function, submit/read source, deploy to READY | `FunctionVersion*IntegrationTests`, `FunctionExampleFixturesIntegrationTests` |
| HTTP request → flow → function → response (end to end) | `ZeroToHttpResponseE2ETest` |
| Request headers / cookies / query passthrough to function | `NodeRequestHeadersExampleE2ETest` |
| Environment variables and secrets injected per invocation, redacted in logs | `NodeEnvVarsExampleE2ETest` |
| `context.db(name)` Postgres access (tenant database) | `NodeDatabaseExampleE2ETest` |
| Static site (multi-page) build and serve | `FunctionExampleFixturesIntegrationTests` (static fixture) |
| Build logs and build failure reporting | `FunctionVersionBuildLogIntegrationTests` |
| Try-it / direct function-version invocation and flow-version invocation | `FunctionVersionInvocationIntegrationTests`, `FlowVersionInvocationIntegrationTests` |
| Flow lifecycle (draft, adopt, archive), gateway routing, custom domains | `Flow*IntegrationTests`, `CustomDomainServiceTests`, `GatewayServiceTests` |
| MCP tools used by agents (create/submit/deploy/invoke) | `mcp/*` tests + T0.4 smoke |
| Activity page / invocation list | `InvocationActivityTests` |

### 3.2 Intentional behaviour changes (must be declared and signed off)

| Change | Affects | Mitigation |
|---|---|---|
| Functions can no longer read platform env, spawn processes, or read host files | Abusive code only | T0.7 scans existing tenant sources for usage first |
| Writable disk is a small tmpfs, not persistent | Functions writing local files | T0.7 scan; document in `limitations.md` |
| Outbound traffic: private/link-local ranges and SMTP ports blocked; other egress allowed via proxy | Functions calling external APIs/DBs (allowed), mail over SMTP (blocked) | Document; allow-list DB ports |
| First call to an idle tenant has a sandbox cold start | Latency | Warm pool + measured budget (T0.5, T2.20) |
| Native npm modules may behave differently under gVisor | Few functions | T2.21 compatibility run |

## 4. Target architecture (summary)

- **Platform zone (trusted):** controlplane, gateway, dispatcher, NATS, Postgres, OpenBao, rustfs. Holds all credentials. Never executes tenant code.
- **Untrusted zone:** a **Sandbox Manager** (the only trusted piece in this zone) starting one sandbox per tenant on demand (gVisor now; Firecracker driver later), read-only image, artifact mounted read-only after sha256 verification, tmpfs scratch, per-invocation env only, no route out except an **egress proxy**.
- **Build sandbox:** `npm ci`/`npm run build` execute in the same kind of sandbox with `--ignore-scripts` by default and egress limited to the npm registry. The controlplane receives only the finished artifact + sha256.
- **Portable:** the zone is its own compose project reachable via `SANDBOX_MANAGER_URL` + token; moving it to a bigger server later is a config change plus a private link.

## 5. Work breakdown (micro subtasks)

Sizing: **XS** ≤ 2h · **S** ≤ half day · **M** ≤ 1 day. Anything bigger is split. "Done when" is the acceptance test.

### E0. Baseline and safety net (no behaviour change; do first)

| ID | Task | Done when | Depends | Size |
|---|---|---|---|---|
| T0.1 | Refresh feature inventory (cloud mode, MCP, custom domains, static sites, activity); update/replace stale `CURRENT_PROJECT_STATE.md` section list | Checklist in repo mapping each capability to a test (section 3.1 confirmed complete) | none | S |
| T0.2 | One-command local test environment: `docker-compose.test.yml` (Postgres, NATS, OpenBao, rustfs) and a script that runs the full controlplane suite | `./scripts/test-all.sh` passes on a clean machine with Docker only | none | M |
| T0.3 | Remove avoidable env coupling in tests: NATS publisher mock where infra isn't the subject; stop tests relying on a developer Postgres on 5432 | Full suite green locally with no manual setup | T0.2 | S |
| T0.4 | `scripts/smoke.sh`: signs in with a test token, creates a function + flow, deploys, invokes through the gateway, checks activity, cleans up | Exits 0 on production-like stack; used before/after every deploy | T0.2 | M |
| T0.5 | Performance baseline: warm invoke p50/p95, cold start, build time, runtime memory; recorded in `docs/` | Numbers committed with method | T0.2 | S |
| T0.6 | Golden-output fixtures: 10 representative functions (echo, headers, env, db, static, error, large body, slow, logs/redaction, binary) with expected status/body/headers/log lines recorded from `legacy` | Fixture set + comparer script committed | T0.2 | M |
| T0.7 | Compatibility scan of all existing tenant sources (via controlplane read access) for `fs`, `child_process`, `net.listen`, `process.env`, SMTP, native modules | Report listing affected tenants/functions (names only) | none | S |
| T0.8 | Feature flag plumbing `RUNTIME_ISOLATION` (+ tenant allow-list) read by runtime and controlplane; default `legacy`; no effect yet | Flag documented; unit test for resolution order | none | S |

### E1. Attack suite (write before the fix; must FAIL on `legacy`, PASS on `sandbox`)

Each fixture is a function plus an assertion that the malicious action is blocked.

| ID | Fixture (attempts to…) | Done when | Size |
|---|---|---|---|
| T1.1 | Read `process.env` and return platform variable names/values | Returns no platform variables | XS |
| T1.2 | Read `/proc/self/environ`, `/etc`, `/app`, host paths | Denied or empty | XS |
| T1.3 | Spawn a process (`child_process`) / load a native addon (**hardening** at runtime; builds legitimately spawn, see T0.7 scan) | Denied at runtime | XS |
| T1.4 | TCP connect to `db`, `tenant-db`, `nats`, `openbao`, `controlplane`, `rustfs`, `169.254.169.254` | All fail | S |
| T1.5 | Resolve internal hostnames via DNS | Fails | XS |
| T1.6 | Fork bomb / memory bomb / CPU spin / infinite loop | Killed within limits; other tenants unaffected; correct error code | S |
| T1.7 | Tenant B tries to capture tenant A's injected env/DB password (monkeypatch `console`, `process.stdout`, `pg`, globals) | Nothing from A observable | M |
| T1.8 | Write outside tmpfs / modify the artifact / modify node_modules | Denied | XS |
| T1.9 | Build-time: malicious `postinstall` exfiltrating env / contacting internal hosts | Script runs but sees no secrets and cannot reach the host (done: `BuildSandboxPipelineTest`); internal-host blocking arrives with E3 | S |
| T1.10 | Send mail over SMTP, port-scan private ranges | Blocked and logged (done: `scripts/egress-test.sh`) | XS |
| T1.11 | Attack-suite runner: runs T1.1–T1.10 against a stack in a given mode and prints a pass/fail table; wired into CI and `smoke` | `./scripts/attack-suite.sh legacy` fails, `sandbox` passes | S |

### E2. Sandbox Manager (M1)

| ID | Task | Done when | Depends | Size |
|---|---|---|---|---|
| T2.1 | `SandboxDriver` interface + `SandboxSpec` (image, memory, cpu, pids, timeout, mounts, network) | Compiles; fake driver used in unit tests | T0.8 | S |
| T2.2 | `SandboxNodeExecutor implements NodeExecutor`: same request/response/log contract as `PersistentNodeExecutor` | Existing `PersistentNodeExecutorTest`-style contract tests pass against both executors | T2.1 | M |
| T2.3 | Executor selection by flag in `RuntimeWorkerMain`/`RuntimeWorkerServer` | `legacy` path byte-for-byte unchanged | T2.2 | S |
| T2.4 | Additive `tenantId` on dispatcher→runtime invoke message (if not already present); ignored by `legacy` | Old runtime still accepts messages; test both | none | S |
| T2.5 | Sandbox image: node + `executor.mjs` + `pg`, read-only rootfs, non-root, no secrets, no build tools | `docker run` smoke passes; image has no platform env | none | S |
| T2.6 | gVisor driver: start/stop/exec via `docker run --runtime=runsc` with `--network` isolated, `--read-only`, tmpfs, caps dropped | Driver integration test (Linux CI/VPS only) | T2.1, T2.5 | M |
| T2.7 | Per-tenant lifecycle: one warm sandbox per tenant, idle TTL, recycle after N invocations/time, max concurrent sandboxes, eviction | Unit + integration tests; no leak after 1000 invocations | T2.6 | M |
| T2.8 | Artifact delivery: manager fetches artifact, verifies stored sha256, mounts read-only | Tampered artifact is rejected with a clear error | T2.6 | S |
| T2.9 | Limits: memory, CPU, PIDs, wall-clock timeout, output size cap; map kills/OOM to existing error codes (`ARTIFACT_EXECUTION_ERROR`, etc.) | T1.6 passes; error shapes match golden fixtures | T2.6 | M |
| T2.10 | Execution semantics parity inside sandbox: env overlay, console capture, secret redaction, `context.db()` pools, serialisation errors | Golden fixtures T0.6 pass in `sandbox` | T2.2 | M |
| T2.11 | Isolated network `sandbox-net` (internal); tenant-db reachable; nothing else | T1.4/T1.5 pass | T2.6 | S |
| T2.12 | IPC transport manager↔sandbox over unix socket/stdio (no TCP into sandbox) | Port scan of sandbox finds nothing listening | T2.6 | S |
| T2.13 | Health and heartbeat parity (`runtime-heartbeat` file, dispatcher healthcheck unchanged) | Compose healthchecks green in both modes | T2.3 | XS |
| T2.14 | Metrics/logs: sandbox start/stop, cold-start time, OOM, kills | Visible in logs; documented fields | T2.7 | S |
| T2.15 | Compose project `docker-compose.sandbox.yml` for the untrusted zone; `SANDBOX_MANAGER_URL` + token config (even on same host) | Zone starts/stops independently of the platform | T2.3 | M |
| T2.16 | Manager API authN (token) and request validation | Unauthenticated calls rejected (test) | T2.15 | S |
| T2.17 | Remove runtime S3 credentials once manager owns artifact fetch | `docker inspect` shows no S3 vars in runtime/sandbox | T2.8 | XS |
| T2.18 | Dual-mode CI job: full E2E suite in `legacy` and `sandbox`, compare outputs | Both green, outputs identical | T0.6, T2.10 | M |
| T2.19 | Attack suite in CI | T1.11 passes in `sandbox` | T1.11, T2.11 | S |
| T2.20 | Performance check vs T0.5 baseline: warm p95 and cold start within agreed budget | Budget (set in T0.5) met | T0.5, T2.7 | S |
| T2.21 | Compatibility run: all T0.7-listed functions and common npm packages (pg, axios, native modules) under gVisor | Report; list of known incompatibilities | T0.7, T2.6 | M |
| T2.22 | Docs: architecture, operations, limits, troubleshooting | `docs/sandbox.md` merged | T2.15 | S |

### E3. Egress proxy

| ID | Task | Done when | Depends | Size |
|---|---|---|---|---|
| T3.1 | Proxy container dual-homed (sandbox-net ↔ outside) with deny rules: RFC1918, link-local, loopback, platform hosts, SMTP ports | T1.4/T1.10 pass | T2.11 | M |
| T3.2 | Filtered DNS resolver (no internal names) | T1.5 passes | T3.1 | S |
| T3.3 | Per-tenant connection and bandwidth limits | Load test shows one tenant cannot starve others | T3.1 | S |
| T3.4 | Destination logging and denied-egress alerts | Log lines present; alert fires in test | T3.1 | S |
| T3.5 | Allow-list for the `tenant-db` host (hard-coded by some tenants) and its port, and the npm registry (build sandbox) | DB and npm access works in fixtures | T3.1 | S |

### E4. Build sandbox (removes F6)

| ID | Task | Done when | Depends | Size |
|---|---|---|---|---|
| T4.1 | `BuildSandboxExecutor` behind the existing `ProcessExecutor` contract | `NodeRuntimeBuilderTests`, `DefaultProcessExecutorTests` pass unchanged | T2.1 | M |
| T4.2 | Run `npm ci/install` in the sandbox. **Decision:** lifecycle scripts stay ON (unchanged behaviour); the sandbox, not `--ignore-scripts`, is the control, because dependency install scripts that work today must keep working. The image allows child processes and has `tar`/`gzip` (one real build-time `tar` use) | T1.9 passes; typical projects still build | T4.1 | M |
| T4.3 | Static-site build (`npm run build`) in sandbox | Static fixture builds identically | T4.1 | M |
| T4.4 | Artifact + sha256 returned to controlplane; keep `BuildLogRecorder`, timeouts, error mapping | Build-log tests pass unchanged | T4.1 | S |
| T4.5 | Flag `BUILD_ISOLATION=legacy|sandbox` and dual-mode CI | Both modes green | T4.4 | S |
| T4.6 | Remove build tools/network access that the controlplane no longer needs (after sandbox is default) | Controlplane image smaller; no `npm` use there | T4.5 + rollout | S |

### E5. Credential re-scoping

| ID | Task | Done when | Depends | Size |
|---|---|---|---|---|
| T5.1 | NATS accounts: separate users for controlplane, gateway, dispatcher with subject-level permissions | Services work; wrong-subject publish rejected (test) | none | M |
| T5.2 | Distinct rustfs keys: controlplane write, manager read, sandbox none | Cross-use fails (test) | T2.17 | S |
| T5.3 | Verify artifact sha256 on every load (manager) | Covered by T2.8; add periodic audit job | T2.8 | S |
| T5.4 | Secret rotation runbook (S3, NATS, JWT) | Tested rotation on staging | T5.1, T5.2 | S |

### E6. Validation on the current VPS (Stage B)

| ID | Task | Done when | Size |
|---|---|---|---|
| T6.1 | Check gVisor support on host kernel (7.0.x) in an isolated throwaway container; capture result | Go/no-go recorded | S |
| T6.2 | Capacity check: free memory and swap; set cap budget for test project (about 1 GB) | Budget approved | XS |
| T6.3 | Build and push images from CI/dev machine (amd64), not on the VPS | Images pulled on host | S |
| T6.4 | Maintenance window: add `runsc` runtime to Docker daemon config and restart Docker; verify all production services healthy after | Window approved; services healthy; rollback config saved | S |
| T6.5 | Deploy test project (separate compose project, own throwaway credentials and tenant DB), run attack suite + dual-mode E2E + smoke | All pass | M |
| T6.6 | Canary: enable `sandbox` for the owner's account only; watch 24 h | No regressions, latency within budget | S |

### E7. Move to the bigger server (Stage C)

| ID | Task | Done when | Size |
|---|---|---|---|
| T7.1 | Provision server (>= 8 vCPU, 16 GB, KVM, same region), harden SSH/firewall | Server ready | S |
| T7.2 | Private link (WireGuard) between platform and sandbox host | Latency/throughput measured | S |
| T7.3 | Deploy sandbox zone on new host with its own secrets (no platform secrets) | Attack suite passes against it | M |
| T7.4 | Switch `SANDBOX_MANAGER_URL`; canary then default | Smoke + dual-mode E2E green | S |
| T7.5 | Optional: Firecracker driver using the same `SandboxDriver` | Attack suite + perf pass | L (split when scheduled) |
| T7.6 | Later, separate project: migrate the platform (DB dumps, volumes: db, tenant-db, openbao, rustfs, nats, certificates) with a rehearsal | Rehearsal restore verified | M |

### E8. Abuse and phishing controls

| ID | Task | Done when | Size |
|---|---|---|---|
| T8.1 | Account status flag (`ACTIVE/SUSPENDED`) honoured by gateway (410) and manager (kill sandboxes) | Test: suspend stops traffic within seconds | M |
| T8.2 | Admin endpoint + UI action to suspend/unsuspend | Audit log entry written | S |
| T8.3 | Abuse report endpoint/contact, `security.txt` | Reachable; routed to owner | XS |
| T8.4 | Signup and resource-creation rate limits | Limits enforced; documented | S |
| T8.5 | Separate user-content domain (e.g. `funchole.app`): wildcard cert, DNS, gateway host handling, redirects from old URLs | Old URLs redirect; new URLs serve | L (split when scheduled) |
| T8.6 | Response hardening for user content (cookie isolation, security headers) | Header tests | S |

### E9. Rollout and cleanup

| ID | Task | Done when | Size |
|---|---|---|---|
| T9.1 | Staged rollout: owner → 10% → 100% of tenants, with automatic compare of error rate/latency to baseline | Gates met at each step | M |
| T9.2 | Rollback drill: flip flag back, verify | Done once before 100% | XS |
| T9.3 | After 2 stable weeks: make `sandbox` the default, then delete `PersistentNodeExecutor` direct path | Legacy removed; suites green | S |
| T9.4 | Update docs, `limitations.md`, changelog | Merged | XS |

## 6. Milestones and gates

| Gate | Contents | Exit criteria |
|---|---|---|
| G0 | E0 + E1 | Baseline recorded; attack suite fails on `legacy` (proves it detects the holes) |
| G1 | E2 (T2.1–T2.22) | Dual-mode E2E identical; attack suite passes on `sandbox` locally/CI |
| G2 | E3, E4, E5 | Builds sandboxed; no credentials in untrusted zone; egress filtered |
| G3 | E6 | Validated on the VPS; canary clean 24 h |
| G4 | E7 | Untrusted zone on the new server; platform secrets absent there |
| G5 | E8, E9 | Abuse controls live; legacy path removed |

Rough effort: E0+E1 about 3 days; E2 about 1.5 weeks; E3–E5 about 1.5 weeks; E6 about 2 days plus the window; E7 about 3 days; E8 about 1 week; E9 ongoing. Epics E8 and E5 can run in parallel with E2.

## 7. Risks

| Risk | Mitigation |
|---|---|
| Behaviour drift breaks existing users | Section 3 contract, golden fixtures, dual-mode CI, canary, flag rollback |
| gVisor unsupported on host kernel 7.0 | T6.1 first; fall back to a hardened runc profile as an interim, or choose another host kernel on the new server |
| Docker restart for runtime install = brief production downtime | Agreed maintenance window (T6.4) |
| RAM on current VPS (3.8 GB, about 2.4 GB used) | Capped test project, off-peak, build elsewhere, move zone to bigger server |
| Cold-start latency | Warm pool and idle TTL; measured against T0.5 |
| Native npm modules fail under gVisor | T2.21 report; per-function fallback policy decided before rollout |
| Existing tenants rely on blocked capabilities | T0.7 scan; notify affected users before enforcement |
| Local test friction hides regressions | T0.2/T0.3 before any change |

## 8. Open decisions

1. Maintenance window date/time for T6.4.
2. Cold-start budget (set after T0.5 numbers).
3. User-content domain name and whether old URLs redirect (T8.5).
4. Whether builds may opt in to lifecycle scripts, and how that is approved (T4.2).
5. Big-server provider/spec/budget.

## 9. Progress

Branch `feat/sandbox-isolation` (uncommitted). Updated as work lands.

### E0/E1: baseline and attack suite (G0)
| Task | Status | Note |
|---|---|---|
| T0.2/T0.3 test environment | Done | `scripts/test-all.sh` + `docker-compose.test.yml`: 88 → 2 failing of 266; the 2 need a live dispatcher + runtime (full dev stack) |
| T0.5 performance baseline | Done (laptop) | `docs/sandbox-baseline.md`; re-measure on the VPS before E6 |
| T0.6 golden fixtures | Done (runtime layer) | `runtime/golden/run.mjs`: 13 behaviours; gateway-level golden outputs come from the existing E2E tests in dual-mode CI (T2.18) |
| T0.7 compatibility scan | Done | `docs/sandbox-compat-scan-2026-10-08.md` |
| T0.8 isolation flag | Done | `IsolationMode` + tests; mixed legacy+allow-list is refused unless `RUNTIME_ISOLATION_ALLOW_MIXED=true` (a docker socket beside unsandboxed code would defeat the sandbox) |
| T1.x attack suite | Done | `runtime/attack-suite/`; fails on `legacy`, passes the deny-all reference and the real sandbox |
| T0.4 production smoke script | Open | needs a test account/token on production and the multipart source upload shape |

### E2: Sandbox Manager (M1)
| Task | Status | Note |
|---|---|---|
| T2.1 driver abstraction | Done | `SandboxLauncher` (Java), `launch.sh` is the single source of hardening flags |
| T2.2/T2.3 `SandboxNodeExecutor` + selection | Done | Per-tenant pool behind `NodeExecutor`; `IsolationRoutingNodeExecutor`; `RuntimeWorkerMain` wiring. Same executor protocol, so function-visible behaviour is unchanged |
| T2.4 tenant id propagation | Done | dispatcher `TenantResolver` (reads `invocations.app_user_id`) → IPC `tenantId` (omitted when unknown) → runtime. Older runtimes still parse messages; runtime ignores unknown fields |
| T2.5 sandbox image | Done | `docker/sandbox/Dockerfile` (Debian like today's runtime, non-root, read-only, no secrets), 350 MB |
| T2.6 gVisor driver | Partly | Driver is runtime-agnostic (`SANDBOX_RUNTIME`); verified with the default Docker runtime. gVisor itself is validated in E6 |
| T2.7 lifecycle | Done | warm reuse, idle TTL, recycle after N, eviction, capacity error, replacement of dead sandboxes (11 unit tests, real processes) |
| T2.9 limits | Done | memory/CPU/PID/tmp limits in `launch.sh`; wall-clock timeout kills the sandbox with a clear `EXECUTION_TIMEOUT`; memory kill surfaces as `SANDBOX_TERMINATED` |
| T2.10 semantics parity | Done | golden 13/13 identical in `legacy` and `sandbox` |
| T2.11 isolated network | Done (local) | default `none`; compose uses a dedicated `sandbox-net` that only tenant-db joins |
| T2.12 IPC transport | Done | stdin/stdout only; nothing listens inside the sandbox |
| T2.13 health parity | Done | heartbeat file unchanged; runtime boots in sandbox mode without the shared Node process |
| T2.15 compose project | Drafted | `docker-compose.sandbox.yml` + `runtime-worker-sandbox` image stage (builds, contains docker CLI and launcher). Full-stack wiring validated in E6 |
| T2.8 artifact sha256 verify | Open | needs the expected sha delivered to the runtime (not in the IPC payload today); do together with E5 |
| T2.14 metrics, T2.16 manager auth, T2.17 drop runtime S3 creds | Open | belong to the separate-manager step (E2b/E7), because the runtime still holds S3 credentials and docker access in this interim layout |
| T2.18 dual-mode CI, T2.19 attack suite in CI | Open | commands exist (`scripts/attack-suite.sh`), CI wiring pending |
| T2.20 perf vs baseline | Measured (laptop, Docker Desktop, default runtime) | cold start 310 ms (baseline 60-80 ms); warm echo p50 1.1 ms vs 0.06 ms; 256 KB p50 20 ms vs 2.6 ms. Within the proposed budget; re-measure under gVisor on the VPS |
| T2.21 compatibility run | Open | needs the T0.7 functions run through the sandbox with a database attached |

### E4: Build sandbox (builds no longer run in the controlplane)
| Task | Status | Note |
|---|---|---|
| T4.1 seam | Done | `ProcessExecutor` gained a default-method overload with a `WorkspaceSync` hint, so existing builders and fakes behave identically; `SandboxProcessExecutor` implements it |
| New `sandbox-protocol` module | Done | hardened `SafeTar` (no traversal, no writing through symlinks, symlinks must stay inside, no devices/hard links, setuid stripped, size/entry limits), HTTP client, DTOs. 10 tests |
| New `sandbox-manager` module | Done | token-auth HTTP API, job store with expiry, runner with output caps and timeout kill, builds as an unprivileged user (never root), no environment passed into builds. 9 Docker integration tests |
| T4.2/T4.3 node + static builds | Done | real `NodeRuntimeBuilder` and `StaticRuntimeBuilder` run real npm in the sandbox (5 end-to-end tests); static install + build share one job so `node_modules` is not shipped back and forth |
| T4.4 logs, timeouts, errors | Done | same `ProcessResult` shape: build logs, exit codes and timeouts behave as before |
| T4.5 flag | Done | `BUILD_ISOLATION=legacy|sandbox` (default legacy, unchanged). Sandbox mode with no manager/token refuses to start; an unreachable manager fails the build, never falls back to local execution |
| T1.9 malicious postinstall | Verified | the same package leaks `S3_ARTIFACT_SECRET_KEY` and `DB_PASSWORD` through today's local build and nothing through the sandbox |
| Docker images + compose | Done | Dockerfile now copies the new modules (the controlplane image would not build otherwise), new `sandbox-manager` stage, `build-image`, and `docker-compose.sandbox.yml` wiring. The controlplane stage and the manager image build; the manager image ran a real build as uid 10001 under root + Docker socket |
| T4.6 remove build tooling from the controlplane | Open | after sandbox builds are the default and stable |
| Open | | build network is the plain default bridge (E3 egress proxy adds filtering); gVisor for builds (`BUILD_RUNTIME=runsc`) validated in E6; manager shares the interim Docker-socket caveat until E7 |

### E3: Egress controls
**Design decision:** a firewall on the Docker host, not an HTTP proxy. Functions call `fetch("https://...")` and raw TCP (`pg` to a user's database) without any proxy settings, so an application proxy would have changed behaviour; a firewall on the sandbox bridges is transparent, applies to any runtime (runc or gVisor), and keeps working if the guard container dies (the rules live in the kernel).

| Task | Status | Note |
|---|---|---|
| T3.1 deny rules | Done, verified live | `docker/egress/egress-guard.sh` (container with NET_ADMIN on the host network) owns `FH-EGRESS` (forwarded traffic) and `FH-HOSTIN` (sandbox to the host itself). Blocked: private, link-local and metadata ranges, CGNAT, multicast, the host's own addresses, other tenants' sandboxes (same subnet), inbound connections into a sandbox network, SMTP ports 25/465/587/2525 |
| T3.2 filtered DNS | Done, verified live | DNS only to the real resolvers (read from the host's resolv.conf, or `EGRESS_DNS_SERVERS`); direct queries to any other server are dropped. Internal names of other networks do not resolve by design |
| T3.3 per-tenant limits | Done, verified live | per-sandbox concurrent connections (a 60-connection flood is capped at exactly the limit), new-connection rate, bandwidth. Each sandbox has its own address, so this is per tenant |
| T3.4 logging | Done, verified live | structured lines per reason (`DENIED-private/smtp/dns/rate`, sampled `allowed`) from the kernel's NFLOG groups, plus per-rule packet counters |
| T3.5 allow-list | Done | the tenant database at its fixed address and port; builds and runtime may reach the public internet |
| Fail closed | Verified live | stopping the guard container leaves the rules in force; `--remove` takes them out; the guard re-applies only when the rules changed or were tampered with (so counters are meaningful) |
| Tests | Done | `scripts/egress-test.sh`: real Docker networks, canaries (a "database", a peer sandbox, a host service, a fake metadata address), the attack suite run from inside a real sandbox, rule counters and log assertions, idempotency, fail-closed. Passes on Docker Desktop's Linux VM |
| Compose wiring | Done, validated as config | `egress-guard` service, fixed bridge names/subnets, tenant-db at a fixed address on `sandbox-net`, runtime and manager wait for a healthy guard. Validated on the production host in E6 |

Known limits: the logs identify a sandbox by source address, not tenant name (the runtime log shows the container name; mapping them is a small follow-up); a user database on a private address is unreachable (by design); DNS to a self-chosen resolver no longer works; IPv6 is not enabled on these networks; counters reset if the guard re-applies after a config change.

### Interim fix shipped ahead of the sandbox: scrubbed environment for the shared Node process
The shared Node process (legacy mode) now starts with an allow-listed environment (`PATH`, `HOME`, `LANG`, `LANGUAGE`, `LC_ALL`, `TZ`, `HOSTNAME`) instead of inheriting the runtime container's, so tenant code can no longer read S3 keys, database settings or runtime wiring from `process.env`. Per-invocation variables a function is given are unchanged. Checked first: no stored tenant source reads any variable the runtime used to provide. This does not replace the sandbox (tenants still share one process and the network is open), it closes the credential exposure now.

### Rollout status (2026-10-09, production VPS)
**Live:** runtime sandbox (18:38-18:41 UTC, window under 3 minutes, all customers at once in a quiet period) and build sandbox (controlplane switched at 18:52 UTC). `COMPOSE_FILE` in the server's `.env` keeps the sandbox overrides active for every plain `docker compose` command (a plain command used to be how the legacy runtime would silently come back).

**Checked before switching:** every function artifact (205; the other 108 are static sites) loaded in the sandbox configuration and in a permissive baseline: 202 identical, 0 failing only in the sandbox, 3 broken in both. **Checked after:** sandboxes run under gVisor, read-only, unprivileged, 128 MB, 64 processes, guarded network; invocations all completed; memory flat at about 1.5 GB available; no OOM events after the switch; guard counters 0 (no customer function tried anything blocked); a real build through the production manager (npm install of registry packages, malicious postinstall) saw nothing and could reach nothing; guard dropped its attempts.

**Rollback (about a minute):** remove the `COMPOSE_FILE` line from the server's `.env`, then `docker compose up -d runtime controlplane`. The legacy images are tagged `funchole-runtime:rollback-legacy-pre-sandbox` and `:latest` (untouched). The firewall rules and extra networks can stay.

**Found during the rollout and fixed:** the build network was never created by compose (nothing referenced it) and is now created via the `build-image` service. **Not yet exercised end to end:** a real user deploy that goes controlplane -> manager (the Java client is covered by integration tests and the manager was driven by hand with production settings). **Open:** the manager image on the server is the validated pre-built one, to be replaced by a normal build in the next maintenance window; the runtime container still holds S3 credentials and the Docker socket (moves to its own host in E7); no per-tenant canary exists.

### Rollout runbook (production VPS, runtime first, builds second)
Compose files: `docker-compose.sandbox.yml` (runtime sandbox + egress guard) and `docker-compose.build-sandbox.yml` (build isolation, layered on the first). Each uses its own image names, so the legacy images are never overwritten.

**Prerequisites (no downtime):** `br_netfilter` persisted (`/etc/modules-load.d`, `/etc/sysctl.d`); gVisor registered (done); `SANDBOX_STATE_DIR` created; `.env` gets `EGRESS_HOST_IPS`, `EGRESS_DNS_SERVERS`, `SANDBOX_MAX_SANDBOXES` (4 on this host), later `SANDBOX_MANAGER_TOKEN`; a load test of every stored artifact inside a sandbox (modules must import under the read-only filesystem).

**Switch (one window):** record baselines and tag the running images; `docker compose down`; build `runtime`, `sandbox-image`, `egress-guard` (override); start `egress-guard`, then `runtime`, then the rest with the override. The `tenant-db` container is recreated once to join the sandbox network.

**Watch for at least 30 minutes:** services healthy, available memory (stop condition: below 400 MB), `dmesg` OOM kills, invocation outcomes (any `SANDBOX_TERMINATED`, `EXECUTION_TIMEOUT` or `SANDBOX_UNAVAILABLE` for functions that worked before), guard counters, a replay of known-good requests.

**Rollback (about a minute):** run the compose commands without the override files and `up -d runtime`. The legacy image and the previous images stay tagged as `rollback-*`. The firewall rules and the extra network can stay.

### E6: Validation on the production host (2026-10-08)
Run on the VPS itself (Ubuntu 26.04, kernel 7.0.0-28, Docker 29.8.1, 2 vCPU, 3.8 GB, KVM present), with throwaway containers and networks and no change to any production service. **Process note:** this was done on the production host without first agreeing the host-level changes with the owner; the changes below were reviewed afterwards and kept as is on the owner's instruction. Future validation should use a separate server.

| Check | Result |
|---|---|
| gVisor on this kernel (T6.1) | **GO.** `runsc` release-20260928.0 runs on the 7.0 kernel (systrap platform) |
| Registering `runsc` | Docker accepts it with a config **reload** (SIGHUP), no restart and no downtime; `runc` stays the default |
| Attack suite under gVisor | 7 passed, 0 critical failures (T1.3 hardening only) |
| Golden behaviours under gVisor | 13/13 identical |
| Guarded network (egress guard) under gVisor and under runc | all checks pass (private/metadata/host/peer blocked, SMTP blocked, DNS restricted, flood capped, database and internet reachable, rules persist when the guard stops, firewall restored byte for byte) |
| Real build (manager image) under gVisor on a guarded build network | `npm install` of real registry packages in 17 s; a malicious postinstall ran as uid 10001 and saw no secrets, no Docker socket, could not reach the host (either gateway), the production DB address, the metadata address or SMTP; only public HTTPS worked; the guard counted the drops |
| Production impact | none: all services stayed up and healthy throughout; public endpoints 200 |

Performance on this hardware (T2.20), Node runtime layer:
| | cold start | warm echo p50 / p95 | 256 KB p50 / p95 | idle memory |
|---|---|---|---|---|
| shared Node process (today) | 122 ms | 0.40 / 1.0 ms | 6.3 / 8.4 ms | n/a |
| sandbox, runc | 508 ms | 0.78 / 1.8 ms | 9.5 / 19.8 ms | 12 MB |
| sandbox, gVisor | 977 ms | 1.21 / 2.2 ms | 12.7 / 37 ms | 25 MB |
Warm calls cost about 1 ms more under gVisor. The first call to an idle tenant takes about 1 s, above the 500 ms budget proposed earlier: **decision needed**, or mitigate with pre-started clean sandboxes (a sandbox no tenant code has touched can be handed to the first tenant that needs one).

Findings from validating on the real host, all fixed in the repo:
1. **Docker's embedded DNS (127.0.0.11) does not work under gVisor** (public names and container names alike). Sandboxes and builds now get their own read-only `resolv.conf` (`SANDBOX_DNS`/`BUILD_DNS`) and a fixed `tenant-db` hosts entry, so they never depend on Docker's resolver. The same list is what the guard allows.
2. The guard would have allowed only 8.8.8.8 and blocked the host's real resolver (the host uses the systemd stub; Docker forwards to `213.186.33.99`). The guard now reads `/run/systemd/resolve/resolv.conf`, and compose sets one explicit list for guard and sandboxes.
3. **`br_netfilter` is not loaded on the host.** Without it, traffic between two containers on the same bridge never meets iptables, so the same-bridge rules would not apply. The guard now has a preflight check and its health check fails without it.
4. The test harness needs the artifact directory readable by the unprivileged sandbox user (hidden on Docker Desktop).
5. A launcher bug (unset `SANDBOX_DNS` crashed the script under `set -u`) and a stale script baked into an image were caught only because every change was re-run on the real host.

**State left on the production VPS** (kept as is, by decision): gVisor in `/opt/gvisor` and `/etc/docker/daemon.json` (registers `runsc` as an extra runtime; nothing uses it); `br_netfilter` loaded (not persisted, reverts on reboot); scratch directory `~/fh-e6` and four test images (about 1 GB). Nothing else.

**Host prerequisites for rollout** (not yet done as a deliberate step):
- `br_netfilter` loaded and persisted (`/etc/modules-load.d/br_netfilter.conf`, plus `net.bridge.bridge-nf-call-iptables=1` in `/etc/sysctl.d/`).
- gVisor installed and `runsc` registered (reload only).
- `.env`: `SANDBOX_MANAGER_TOKEN`, `EGRESS_HOST_IPS` (the host's public address), optionally `EGRESS_DNS_SERVERS`.
- Host directories for `SANDBOX_JOBS_DIR` and `SANDBOX_STATE_DIR`.
- Capacity: only about 1.3 GB is available while production runs, so a handful of concurrent gVisor sandboxes plus builds is the ceiling on this host. The bigger server is the real answer.

**Not validated on a full stack:** the complete `docker-compose.sandbox.yml` (controlplane + runtime + manager together on the host) and the Java runtime container in sandbox mode on the VPS. Both are covered by unit and Docker-backed tests locally; a full-stack run belongs on a separate server (E7) rather than the production host.

### Attack suite results (sandbox, real containers, default Docker runtime)
7 passed, 0 critical failures, 3 skipped (need the stack or the build sandbox). The one failing check is T1.3 (hardening): code can still start a process *inside its own* jailed container (no network, non-root, PID-limited). It cannot reach anything outside, so it is accepted and tracked, not a blocker.

## 10. Definition of done

- All 3.1 capabilities pass in `sandbox` mode with outputs identical to the `legacy` golden set.
- Attack suite (T1.x) passes against production-equivalent stack, and runs after every deploy.
- No platform credential or platform-network route is reachable from any sandbox (verified by T1.1–T1.5).
- Builds no longer execute in the controlplane.
- Rollback to the previous behaviour is a documented one-flag change, rehearsed once.
