# MCP Testing Feedback — Coding Agent Function Creation

Feedback from today's testing: a coding agent working through the MCP server still had
trouble understanding how to create a Function and what source/response format is expected,
across three specific areas. This is a feedback/tracking note, not a design doc — no code
changed as part of writing it.

## 10. Real STATIC deploy 500 (not a docs gap) — FIXED

User feedback (2026-09-22): a coding agent's STATIC deploy of a real function
(`e3030154-397c-4d85-a092-916f5bfd7656` / version `26d6a8a8-...`, "PB UI") failed, and the user
suspected the earlier STATIC-instruction doc fixes hadn't actually helped. Investigated live
against the running dev stack (read-only at first, then with explicit permission to restart/fix):

- **The failure was never a documentation problem.** Reproducing the exact submission
  (`package.json` running `node build.js`, `build.js` copying `public/index.html` into `dist/`)
  against the live dev `controlplane` returned a bare `500 Internal Server Error` /
  `UncheckedIOException` - not the structured `BuildFailureException` the MCP docs already
  describe, so no amount of tool-description wording could have prevented or explained it.
- **Found a second, independent bug while investigating: `GlobalExceptionHandler.handleGeneric`
  (`core/.../handler/GlobalExceptionHandler.java`) never logged the exception it caught** - every
  unexpected 500 anywhere in the app was completely silent server-side, by design accident, not
  just this one. Fixed: added `log.error(...)` with the full exception before building the
  response. This is what made root-causing the actual bug possible at all.
- **Root cause, once visible in logs**: `Cannot run program "npm" ... No such file or directory`
  - `npm`/`node` were genuinely missing from the *live* `backend-controlplane-1` dev container.
  Traced to a deployment gap, not a code gap: commit `c0b1fcc` (this session, "install Node in
  production image") was verified end-to-end but only in an isolated, freshly-built test
  environment, per this session's own "never touch the live dev stack" discipline - the actual
  running dev container's image was never rebuilt afterward, so it stayed on a stale image
  predating that fix. (Node was never missing from `dev-base-common` itself for the other
  services - confirmed `backend-runtime-1`, the one other container that actually needs `node` at
  runtime, already had it; this was specific to the stale `controlplane` image.)
- **Fixed**: `docker compose -f docker-compose.dev.yml build controlplane` +
  `up -d controlplane` (rebuilt and recreated only the `controlplane` container - every other
  dev-stack service/volume/data untouched, confirmed via `docker ps` before and after).
  Live-reproduced the *exact* original failing submission against the rebuilt container:
  deploy now returns `status: READY` with a real published artifact
  (`artifacts/edb01461-.../artifact.tar.gz`, 11899 bytes, real sha256).
- Also discovered and worked around, separately: the container's hot-reload watcher
  (`docker/watch-and-run.sh`) had gotten stuck from an earlier, unrelated restart race
  (`kill $APP_PID` doesn't reach the actual JVM grandchild Gradle forks, so a failed restart
  attempt leaves an orphaned server process running stale code with no path back to `docker
  logs`) - worth its own fix (e.g. `pkill -P` the process group, or `exec`-replacing gradle with
  the java process so there's no extra layer to orphan) but out of scope for this pass; a full
  `docker compose restart controlplane` (not just relying on the watcher) recovered it cleanly.

**Takeaway for future fixes in this repo**: a fix verified only in an isolated test environment
is not "deployed" until the actual long-running dev containers are rebuilt too - isolated
verification proves the *code* is correct, not that the *user's running environment* has it.

## Priority signal: STATIC is where agents get stuck the most

Explicit takeaway from today, worth calling out on its own rather than leaving buried across
several numbered items: **coding agents consistently get "disturbed" (confused, stuck,
flailing through workarounds) specifically while producing STATIC pages**, more than any
other part of the Function lifecycle. This isn't a guess - it's the pattern across this
file's own evidence: the wrong-runtime confusion (item 3), the `cp`/`mkdir`-in-build-script
question (item 5), the missing multi-page worked example (item 6), and the real transcript
of an agent spiraling through failed workarounds (item 7a) are *all* STATIC-specific. NODE
functions did not produce anything close to this volume of confusion in today's testing.

Whatever fix pass comes out of this file, **STATIC deployment should get outsized attention
relative to everything else here** - it's the one area actively costing agents the most
wasted turns and failed attempts, not just a documentation nice-to-have.

## What went wrong

1. **Coding agent didn't know the required source format** — got `entrypoint`/`handler`
   wrong, or didn't understand what `submit_function_version_source` expects as input.
2. **Coding agent got the RESPONSE contract wrong** — likely returned a Lambda/API-Gateway
   style `{statusCode, headers, body}` shape instead of FuncHole's actual
   `{"status": <int>, "body": <any JSON value>}` contract.
3. **Coding agent picked the wrong runtime (NODE vs STATIC)** — e.g. tried to serve HTML via
   a NODE function instead of deploying it as STATIC, or the reverse.

## What's already documented (as of this session)

Worth recording precisely, since it means the gap isn't "nothing is documented" - it's that a
real agent still got it wrong *despite* this already being in the live MCP tool descriptions:

- `create_function`'s `runtime` param already explains NODE vs STATIC with concrete framing
  ("NODE runs your handler code - use for a backend/API function" / "STATIC serves a
  pre-built static site's files directly, no code execution - use for a frontend/UI"), plus
  guidance on reusing a STATIC Function for shared assets across sites.
- `create_flow_step`'s description already spells out the RESPONSE contract explicitly:
  *"its Function's handler must return exactly `{"status": <int>, "body": <any JSON
  value>}` - the Gateway reads only those two fields, always JSON-encodes body, and always
  sends `Content-Type: application/json`. Any other fields (e.g. statusCode, headers) are
  silently ignored."* It also explicitly calls out the Lambda-shape mistake and points at
  STATIC as the fix for real HTML.
- `submit_function_version_source`'s description repeats the same RESPONSE contract warning,
  documents `entrypoint`/`handler` (`handler` defaults to `"handler"`), and separately covers
  the STATIC-runtime multi-page/clean-URL convention and its `package.json` + `build` script
  requirement.

(File/line references, all in `controlplane/src/main/java/com/funchole/backend/controlplane/
mcp/`: `FunctionMcpTools.java`'s `create_function`, `FlowVersionMcpTools.java`'s
`create_flow_step`, `FunctionVersionMcpTools.java`'s `submit_function_version_source` and
`create_function_version`.)

## Open question for next pass

Since the documentation above already covers all three flagged areas fairly explicitly, the
next useful step is pinning down *why* it still didn't land for the agent that hit this today
- worth checking, with a real transcript/example from today's session if available:

- Was the agent actually calling `tools/list`/reading the live tool schema, or working from
  stale/cached knowledge of the API shape (e.g. assuming a generic Lambda-style contract by
  default rather than reading FuncHole's own)?
- Did the failure happen on an *older* deployment that predates this session's doc updates
  (a timing/staleness issue, not a content gap)?
- Is there a genuinely missing worked example - i.e., is prose description enough, or does
  the tool description need a literal end-to-end example payload (a full source file +
  expected RESPONSE step output) rather than just explaining the rules?
- For the "wrong runtime" case specifically: was the confusion about *choosing* NODE vs
  STATIC, or about *switching* an already-created Function's runtime after realizing the
  wrong one was picked (a workflow gap, not a documentation gap, if `update_function`'s
  runtime param doesn't make changing this clear)?

## Suggested next step

Get a concrete transcript or reproduction from today's testing (the actual MCP tool calls the
agent made, and what it did wrong) rather than iterating on the descriptions blind - the
existing text already addresses the categories reported, so the fix from here needs to be
targeted at whatever specifically didn't land, not another general pass over the same ground.

## 4. Database seed/schema structure — no example given, and no seeding mechanism at all — FIXED

Flagged separately: a coding agent has no way to know how to actually *use* an attached
Database, or how to get an initial schema/seed data into one in the first place. Checked the
current state (no code changed):

- Every `context.db(name)` mention across the MCP tools (`create_database`,
  `attach_function_version_database`, `attach_flow_database`) is a bare reference with zero
  example of what it actually returns or how to call it. Confirmed in
  `runtime/node/executor.mjs`: `context.db(name)` returns a real `pg.Pool` (node-postgres) -
  so real usage is `const pool = context.db('primary'); const { rows } = await pool.query('select * from orders where id = $1', [id]);` -
  but nothing in any tool description says this, so an agent has to already know the `pg`
  library's API by coincidence rather than being told.
- **There is no MCP tool for running arbitrary SQL against an attached Database at all** - no
  `run_query`/`migrate`/`seed` tool exists (confirmed: no such tool in `DatabaseMcpTools.java`
  or anywhere else). A Database resource in FuncHole is a connection to an *externally
  managed* Postgres instance (FuncHole stores the connection details, not the schema) - so
  the only way a coding agent can currently create tables or seed data is to write a one-off
  Function whose handler runs `CREATE TABLE ...`/`INSERT ...` via `context.db(...).query(...)`,
  deploy it, and invoke it once by hand as an improvised migration step. Nothing tells an
  agent this is the expected pattern, so without being told, there's no way to infer it.

**Fixed and live-verified.** `attach_function_version_database`'s description now includes
the real `context.db(name)` → `pg.Pool` → `.query(sql, params)` call shape (with a literal
code example) and states explicitly that there is no separate migration/seed tool - writing
and invoking a one-off Function that runs DDL/seed SQL via `context.db(...)` *is* the
mechanism, not a workaround for a missing one. `attach_flow_database` and `create_database`
both now point at it rather than duplicating the explanation. Verified live through the real
MCP protocol (`tools/list` against a running instance) - the served schema text matches
exactly what was written.

## 5. Coding agent writes shell commands (`cp`, `mkdir`) into `package.json`'s `build` script — DOCUMENTED

Observed today: a coding agent put raw shell commands like `cp`/`mkdir` directly into a
STATIC Function's `package.json` `"build"` script. Checked the actual build mechanism (no
code changed):

- Confirmed in `StaticRuntimeBuilder.java`: the build step is literally `npm run build`, run
  as a real OS subprocess (`ProcessExecutor` → `ProcessBuilder`, no sandbox - this matches
  the already-tracked, still-open "build/runtime sandboxing" gap in the master backlog).
  Whatever that npm script does - including shelling out to `cp`, `mkdir`, or any other
  binary reachable on the build container's `PATH` - runs exactly as it would on a normal
  local machine. So `cp`/`mkdir` in a build script isn't inherently invalid; nothing about
  FuncHole's build pipeline restricts or special-cases it.
- **Real, separate bug found while checking this**: the *production* `controlplane` Docker
  image (`Dockerfile`'s `controlplane` target, `FROM runtime-base`) has no Node.js/npm
  installed at all - only `dev-base-common` (used by `dev-controlplane` etc.) installs
  Node. `NodeRuntimeBuilder`/`StaticRuntimeBuilder` both run inside the `controlplane`
  process itself (not a separate worker), so in the current production image, **any NODE
  Function with a `package.json` (real dependencies) and every STATIC Function build would
  fail** with `npm`/`node` not found - the only reason this wasn't caught during this
  session's own production-compose testing is that every test function deployed was
  dependency-free (no `package.json` submitted at all), which skips the `npm` call entirely
  (`NodeRuntimeBuilder`'s own comment: "Dependency-free function: no package.json, so npm is
  never invoked"), and no STATIC-runtime function was ever deployed through the production
  compose stack this session. This is a genuine gap in `PRODUCTION_DEPLOYMENT_MISSING.md`'s
  scope that wasn't caught - **not fixed, not touched, per your instruction** - just flagged
  here since it surfaced while investigating this feedback item.
- Given `cp`/`mkdir` in a build script isn't actually invalid, the "trying to push" framing
  may point at something else worth clarifying rather than a real error: possibly the agent
  wasn't sure this was allowed/supported and was working around perceived restrictions that
  don't exist, or was manually re-implementing static-asset copying instead of trusting a
  standard bundler's own `public/`-folder convention (Vite/CRA already copy static assets
  into the output directory automatically - manual `cp`/`mkdir` in `build` is usually only
  needed for a from-scratch/no-bundler setup). Worth a concrete example from today's
  transcript to know which one this actually was.

**(a) fix the production image's missing Node.js/npm** - fixed, see item 7a.

**(b) clarify build-script flexibility - fixed and live-verified.**
`submit_function_version_source`'s description now states explicitly that the build step is
real/unrestricted (`npm ci`/`npm install` then `npm run build`, in that fixed order, no way
to skip or override either), that the `"build"` script itself can run any shell command
exactly as it would locally (nothing STATIC-specific to work around), but that Node/npm
itself is a hard requirement for every STATIC deploy regardless. Didn't go further into
"bundler convention vs. manual copying" guidance since no concrete transcript surfaced to
confirm which one the agent actually needed - the doc note above still applies if that
comes up again. Verified live through the real MCP protocol.

## 6. Missing: a concrete worked example of a multi-page STATIC submission's file layout — FIXED

Flagged today: agents need actual MCP tool guidance for the "multiple pages in a single
STATIC Function" format, not just the abstract resolution rule. Confirms the open question
already raised in item 3 above ("is prose enough, or does this need a literal example?").

Checked the current `submit_function_version_source` description (no code changed): it
explains the clean-URL *resolution rule* in prose - `/about` resolves to `about`, then
`about.html`, then `about/index.html`, in that order - but never shows what an actual
multi-page submission's file list looks like. An agent has to mentally reverse-engineer "if
`/about` resolves to `about.html`, that means I should submit a file literally named
`about.html`" from the resolution rule alone, instead of seeing it stated directly.

**What's needed:** add a concrete worked example to `submit_function_version_source`'s
description (and/or `create_function`'s STATIC guidance) showing an actual multi-page file
list for one Function - e.g. something like:

```
files: [
  { path: "index.html", ... },        // "/"
  { path: "about.html", ... },        // "/about"
  { path: "contact.html", ... },      // "/contact"
  { path: "blog/index.html", ... },   // "/blog"
  { path: "blog/first-post.html", ... } // "/blog/first-post"
]
```

paired with a one-line statement that this is all *one* Function/FunctionVersion submission
(not one Function per page) - since the earlier feedback (see the original session note this
built on) was specifically that agents kept creating a separate `/`-only Flow per page
instead of one multi-page site, and the rule-only prose evidently isn't enough on its own to
stop that pattern from recurring.

**Fixed and live-verified.** Added a five-file worked example to
`submit_function_version_source`'s description, with each file's path paired inline with the
route it serves (`index.html` -> `/`, `about.html` -> `/about`, `blog/index.html` -> `/blog`,
`blog/first-post.html` -> `/blog/first-post`), plus an explicit "one Function, one
submission, not one Function per page" statement. Verified live through the real MCP
protocol - the served schema text matches exactly what was written.

## 7. Real transcript evidence — confirms item 5, plus a new, more serious bug

A real agent transcript from today's testing surfaced two distinct, concrete failures. No
code changed - this is capturing evidence, not a fix.

### 7a. Confirms item 5: `npm` genuinely isn't available in the build environment — FIXED

The agent's actual build attempt failed with **"Failed to execute command: npm, install"** -
this is the exact failure item 5 predicted from reading the code (production `controlplane`
has no Node.js/npm installed), now confirmed with a real error message rather than just
static analysis. The agent's own recovery attempt made it worse, not better: it reasoned
"since npm isn't available... maybe I can use a simpler `package.json` without dependencies"
and retried - but `StaticRuntimeBuilder.runBuildScript` always runs `npm run build`
unconditionally for STATIC (unlike `NodeRuntimeBuilder`'s dependency install step, which
skips entirely when there's no `package.json`, STATIC's build step has no such skip - it's
not optional, a static site can't be built without running its bundler). So a dependency-free
`package.json` would not have rescued this agent even if it had gotten that far - the
identical `npm run build` failure would recur immediately. This is worth fixing (installing
Node in the production image) with higher priority than previously assessed, now that it has
directly broken a real agent session rather than being a theoretical gap.

**Further transcript detail (same session, continued):** after the first failure the agent
kept trying variations instead of stopping - considered `npx`, then created an entirely new
FunctionVersion and tried to use `sh -c` "as the build command to avoid needing npm." That
attempt was never going to work either: there is no build-command override parameter
anywhere in `create_function_version`/`submit_function_version_source` - `StaticRuntimeBuilder`
always runs `npm ci`/`npm install` first, unconditionally, before anything else, regardless
of whether `package.json` declares any real dependencies (confirmed in
`installDependencies()`: it only checks that `package.json` *exists*, not whether its
`dependencies` are non-empty - `npm ci`/`npm install` with zero dependencies still requires
the `npm` binary itself to run). So there is currently no way to deploy a STATIC site at all
without a working `npm`/`node` in the build environment, even a trivial hand-written
HTML/CSS site with no bundler and no real dependencies - the pipeline has no escape hatch,
and nothing in the MCP docs states this rigidity explicitly (that the build command is
always exactly `npm run build`, with `npm ci`/`npm install` always run first, with no
override). This compounds the missing-Node production bug: fixing that bug removes the
immediate blocker, but the *design* still forces every STATIC deployment through npm with
zero flexibility, which is worth being explicit about even after the environment is fixed.

**Fixed and live-verified.** Root cause: the production `Dockerfile`'s `controlplane` target
based itself on `runtime-base` (curl/jq only), not `runtime-node-base` (which already
installs Node 22 via nodesource - the same base `runtime-worker` already used). One-line
fix: `controlplane`'s `FROM` changed from `runtime-base` to `runtime-node-base`.

Verified live end-to-end, in an isolated stack, reproducing the *exact* failing scenario
from the real transcript above (not a simplified stand-in):
- Confirmed `node --version`/`npm --version` now resolve inside the built `controlplane`
  image (`v22.23.2`/`10.9.8`).
- Submitted a real STATIC FunctionVersion with a `package.json` whose `build` script is
  literally `mkdir -p dist && cp index.html dist/index.html` - the same `cp`/`mkdir`
  pattern from item 5/7a, deliberately reproducing what an agent would write.
- Deployed it: `npm ci`/`npm install` and `npm run build` both ran for real this time,
  producing a real 257-byte artifact published to RustFS, version status `READY` (this
  exact deploy call previously failed with "Failed to execute command: npm, install").
  Note this doesn't touch item 7a's separate finding that there's still no way to override
  the build command - that remains open, just no longer blocked by npm being entirely
  absent.
- Routed a real domain/gateway/Flow to it and confirmed the Gateway serves it over real
  HTTPS: `curl` returned `200` with the exact HTML the `cp`/`mkdir` build script produced.
- Confirmed the user's actual running dev stack (`backend-*` containers) was completely
  unaffected throughout - verification ran in a fully separate, isolated Compose project,
  torn down afterward (containers, volumes, network all removed).

### 7b. New: submitted source can silently disappear ("metadata survives" but content doesn't) — FIXED

Separately, the same agent tried to inspect an *existing* function version's source via
`get_function_version_source` and got: **"Source content is no longer available for
function version ... (file package.json is missing from storage, though its metadata
survives)."** Traced to `LocalSourceStore.java:63-64` - this is the same architectural gap
already noted during this session's earlier production-compose work: `LocalSourceStore` is
the *only* `SourceStore` implementation that exists, and it's plain local disk (defaults to
`/tmp/funchole-sources`, ephemeral unless a persistent volume is mounted). This transcript is
now direct, live evidence of that gap actually destroying a coding agent's own submitted
source mid-session, not just a theoretical durability concern - the version's DB row/metadata
still exists (so the agent can see the version "exists"), but its actual file content is
gone, with no indication of *why* or *when* it disappeared. This is more severe than a build
environment gap: it's silent, unrecoverable data loss for content the agent itself submitted
earlier in the very same session.

**Independently verified directly against the running dev stack (not just inferred from
code) - confirmed genuinely missing, not a query bug or false alarm:**

- `function_versions` row for `3a4522a6-5df7-41b9-92ca-3bda2c6344c4` is real and intact:
  `status = READY`, `created_at = 2026-09-20 11:57:59`.
- `function_version_sources` row is also intact, claiming `relative_paths =
  ["package.json", "index.html"]`.
- `SOURCE_STORAGE_ROOT` is unset in the dev container, so it resolves to the code default
  `/tmp/funchole-sources`. Listed that directory directly inside the live `controlplane`
  container: it has 16 version subdirectories, and **every single one has a timestamp from
  the same day (Sep 21, 12:37-13:19)**. This version's directory, which should date from
  Sep 20, is not there at all.
- That timestamp pattern is the root cause, not just the symptom: `/tmp` inside a container
  is wiped on container *recreation* (a `docker compose down`/`up`, a Docker Desktop
  restart, or a host reboot) - not on the plain JVM-process restarts `watch-and-run.sh` does
  for ordinary source-change reloads, which leave `/tmp` untouched. Postgres survived across
  whatever recreation happened because it's on a real named volume
  (`postgres-dev-data`); `/tmp/funchole-sources` didn't because it isn't backed by
  anything. Every source file submitted before that recreation boundary was silently wiped,
  while every DB row pointing at those files was left intact and none the wiser.

**Follow-up question that came up: if the source is gone, how is the deployed site (e.g.
`https://a65ey3.funchole.test/`) still live?** Verified directly, not assumed - this is
*not* the same bug resurfacing, it's a genuinely different storage path:

- That URL's `/` route resolves to `flow_version 43ef4661-df0c-48d0-8258-c9a1c908c7e2`, whose
  step points at `function_version 3a4522a6-5df7-41b9-92ca-3bda2c6344c4` - **the exact same
  version whose source is missing.** But it has its own, separate, intact row: `runtime =
  STATIC`, `artifact_object_key = artifacts/3a4522a6-.../artifact.tar.gz`,
  `artifact_size_bytes = 4504`, `artifact_published_at = 2026-09-20 11:58:56`.
- Confirmed live: `curl`-ing that URL right now returns real HTML/CSS (14010 bytes) - the
  Gateway is successfully fetching and serving this artifact this very moment.
- **Source and artifact are two entirely separate storage systems, and only one of them is
  durable.** Source (the raw submitted files, pre-build) lives in `LocalSourceStore` -
  ephemeral `/tmp` on `controlplane`, as established above. The *artifact* (the built
  output - what `StaticRuntimeBuilder` produces after running `npm run build`, tar'd up) is
  published to RustFS/S3, a real service with its own persistent volume, and the Gateway
  serves requests straight from there - it never touches `LocalSourceStore` at request time.
  So the one deploy that succeeded on Sep 20 (source → build → artifact → publish to RustFS)
  left a durable artifact behind that's kept serving fine ever since, completely unaffected
  by whatever wiped `/tmp` afterward.
- **This makes the bug worse in one specific way, not better: it's invisible until the exact
  moment someone needs to rebuild.** The site can serve correctly for an arbitrarily long
  time after its source is already gone, with zero indication anything is wrong - a coding
  agent (or a person) asking to tweak an existing, working, currently-serving page discovers
  with no warning that there's nothing left to rebuild from.

**(a) install Node/npm** - fixed, see item 5/7a above.

**(b) durable source storage - fixed and live-verified.** Added `S3SourceStore` (new,
`controlplane/.../service/S3SourceStore.java`), a second `SourceStore` implementation
storing each file as its own S3 object (`sources/<functionVersionId>/<relativePath>`) in
the *same* bucket/credentials already required for build artifacts (`app.artifact.*` /
`S3_ARTIFACT_*`) - no new required config for an operator who's already configured that.
`SourceStoreConfig` (new) picks between it and `LocalSourceStore` via a new
`SOURCE_STORE_TYPE` property (`local` default - zero behavior change for dev/existing
deployments; `s3` in production `docker-compose.yml` now). Removed the now-redundant
`controlplane-sources` volume/mount that was only ever a partial mitigation for this exact
problem.

Verified live with the same kind of reproduction as item 5/7a - not a simplified stand-in,
the actual failure scenario:
- Submitted real source (`index.mjs`) to a fresh FunctionVersion with `SOURCE_STORE_TYPE=s3`
  active, read it back successfully, and confirmed `/tmp/funchole-sources` doesn't even
  exist in that container (nothing written to local disk at all).
- **Fully removed and recreated the `controlplane` container** - the exact event that
  destroyed the original agent's source (see the timestamp-pattern evidence above) -
  and confirmed the brand new container instance reads back the *exact same source content*
  submitted by the now-destroyed one, correctly, with no data loss.
- Confirmed `deploy_function_version` still works correctly reading source through the new
  S3-backed path (`status: READY`, real artifact published).
- Ran the full test suite: `LocalSourceStoreTests`, `FunctionVersionSourceIntegrationTests`,
  and `FlowFullSourceIntegrationTests` all pass clean (dev/tests still default to
  `SOURCE_STORE_TYPE=local`, unaffected) - the only 2 failures present are already part of
  the established "needs a live Dispatcher/Runtime Worker" baseline, unrelated to this
  change.
- Confirmed the user's real running dev stack was completely unaffected throughout -
  verification ran in a fully isolated Compose project, torn down afterward.

## 8. A stub/example tool as a backup for stuck agents — FIXED

Discussed and agreed on the shape, then scoped and built.

**What was built**, matching the design intent below exactly:

- New MCP tool `get_function_example(scenario)` (`FunctionExampleMcpTools.java`, new), scenario
  one of `NODE_BASIC`, `NODE_DATABASE`, `STATIC_MULTIPAGE`. Returns a runtime, a scenario-specific
  explanation, an entrypoint/handler, and the real file list - shaped so the `files` array can be
  passed straight into `submit_function_version_source`.
- The actual example content lives in `FunctionExampleFixtures.java` (new, main source) as the
  single source of truth for each scenario - both requirement (2) below and requirement (1) are
  satisfied the same way: a real, currently-passing test asserts against these exact constants,
  not a hand-authored copy:
  - `NODE_BASIC_SOURCE` - the same constant now used by
    `FlowVersionInvocationIntegrationTests#invokesADraftFlowVersionWithoutRequiringAdoption`
    (promoted from that test's own inline literal to a shared constant, so the test and the MCP
    tool can never drift apart).
  - `NODE_DATABASE_SOURCE` - proven by a new `NodeDatabaseExampleE2ETest` (tagged `e2e`, same
    in-process Dispatcher/Runtime Worker architecture as `ZeroToHttpResponseE2ETest`): creates a
    real Database resource, attaches it, deploys this exact handler, invokes it through a real
    Dispatcher + a real `node` child process, and asserts the row it creates/inserts/selects via
    `context.db('primary')` round-trips correctly. OpenBao is not required for this test - both
    the secret-save side (`FunctionSecretStore`) and secret-read side (`FunctionSecretReader`)
    are overridden with a single in-memory fake, the same pattern
    `FunctionVersionConfigIntegrationTests` already uses for env/secret config;
    `JdbcFunctionVersionDatabaseResolver` itself is real, unmodified production code.
  - `staticMultipageFiles()` (5 files: `package.json`, `index.html`, `about.html`,
    `blog/index.html`, `blog/first-post.html`) - proven by a new
    `FunctionExampleFixturesIntegrationTests`, which submits this exact file set through the real
    submit endpoint and reads it back byte-for-byte, including the nested `blog/` path.
    Deliberately does **not** additionally run a real `npm run build` in this test (that would
    require `node` on the test machine, matching the repo's existing e2e-only tests, for a
    concern - build-pipeline correctness - that's a different thing from proving this specific
    example's content is real and submittable); the build pipeline itself is already covered by
    `StaticRuntimeBuilderTests` and was live-verified end-to-end for this identical `cp`/`mkdir`
    build-script pattern in item 7a above.
  - A fourth test, `FunctionExampleMcpToolsTests`, exercises the tool bean's own scenario-selection
    logic directly (all three scenarios plus the unknown-scenario error path) - MCP
    transport-level discoverability (auto-registration via `@Service` + `@McpTool`, no explicit
    registration list) is the same mechanism all eleven pre-existing MCP tool classes already use,
    live-verified via the real MCP protocol earlier this session; this pass did not repeat that
    specific protocol-level `tools/list`/`tools/call` check for this one additional tool, relying
    instead on the bean-level test plus the identical, already-proven registration mechanism.
- **Discoverability from the failure itself (requirement 1)**: `BuildFailureException.buildMessage()`
  (`controlplane/.../functionbuild/BuildFailureException.java`) - the single shared message
  constructor both `NodeBuildException` and `StaticBuildException` call - now appends "Call the
  MCP tool get_function_example for a known-working template for this runtime." Confirmed this is
  the correct, and only, injection point: `BuildExceptionHandler`'s richer structured REST 422
  response never fires for MCP tool calls (no `@RestControllerAdvice`-equivalent exists for MCP);
  decompiling `spring-ai-mcp-annotations`' `SyncMcpToolMethodCallback` confirmed an MCP-calling
  agent only ever sees `"Error invoking method: " + exception.getMessage()` - i.e. exactly this
  string - for any `RuntimeException` a tool method lets propagate, which is exactly what
  `deploy_function_version` does today. One edit covers both exceptions and reaches every MCP
  caller, and improves the REST error message for free as the same edit.

Full `./gradlew compileJava compileTestJava` (monorepo-wide) is clean, and
`./gradlew :controlplane:test` (194 tests) shows only one, pre-existing, unrelated failure
(`DefaultProcessExecutorTests` - a flaky child-process-count timing assertion, nothing to do with
this change).

Below is the original design-intent note, kept for context on what was agreed before building:

---

**The idea:** a dedicated MCP tool that returns real, working example source for a given
runtime/use case (a NODE function with the correct `{status, body}` RESPONSE contract, a
STATIC multi-page site's file layout, a Function that reads from an attached Database via
`context.db(...)`, etc.) - giving a stuck agent copy-pasteable ground truth instead of
requiring it to correctly synthesize behavior from prose tool descriptions alone. This
directly targets the actual failure pattern seen in item 7's transcript: the agent didn't
ask for help, it guessed and kept retrying broken workarounds instead. It would also directly
address items 1, 2, 3, 4, and 6 above in one shot, rather than needing separate prose fixes
to each tool description.

**Two design requirements agreed on, not just "add a tool":**

1. **Discoverability has to come from the failure itself, not the tool list.** Just adding
   this as one more of 60+ MCP tools and hoping an agent thinks to call it proactively
   repeats the exact problem the existing (already fairly thorough) prose docs already have -
   items 1-6 above show an agent not reading/following documentation that already exists.
   The highest-leverage placement is surfacing it *from build-failure responses themselves*
   - e.g. `NodeBuildException`/`StaticBuildException` error payloads ending with something
   like "call `get_function_example` for a known-working template" - so it's found exactly
   when an agent is already stuck and looking for a way out.
2. **Examples must be sourced from real, currently-passing fixtures, not hand-authored
   docs-only content.** If the tool's example content is written by hand and lives only in
   the MCP layer, it can silently drift out of sync with actual runtime behavior over time
   (exactly the kind of staleness this whole feedback file exists to catch elsewhere in the
   repo). Pulling the returned example directly from this repo's own passing integration-test
   fixtures means it can never be wrong without a test also failing.

**Not yet decided:** the tool's exact shape/parameters (a single `get_function_example`
covering multiple scenarios via a parameter, vs. several narrower tools), and which specific
build-failure exceptions should reference it. Needs its own scoping pass before
implementation - this section records intent, not a spec.

## 9. Guardrails and sandboxing - guardrail wording DONE, real sandboxing still open

Two related asks: (a) MCP tool descriptions should carry explicit guardrail language so a
coding agent doesn't submit arbitrary/dangerous code, and (b) submitted code should actually
run in a sandbox, not just be discouraged by wording. Checked the current state of both (no
code changed):

- **No guardrail language exists today.** None of the MCP tool descriptions
  (`submit_function_version_source`, `create_function`, etc.) say anything about what's
  unsafe or disallowed to submit - no mention of credential/secret access, network
  exfiltration, resource abuse, or any other boundary. An agent has no signal from the tool
  descriptions themselves that this is even a consideration.
- **No sandboxing exists at either place code actually runs, confirmed by reading both:**
  - *Build time*: `ProcessExecutor` (used by both `NodeRuntimeBuilder` and
    `StaticRuntimeBuilder` for `npm ci`/`npm install`/`npm run build`) is a raw
    `ProcessBuilder.start()` call - see item 5. Whatever a submitted `package.json` build
    script does runs with the same OS-level access as the `controlplane` process itself.
  - *Invocation time*: `PersistentNodeExecutor` (the actual Node function execution) is
    *also* a raw `ProcessBuilder.start()` call, spawning one long-lived `node executor.mjs`
    process that stays warm and handles many executions over time. This means there is
    currently no isolation *between different Functions or different users' code* either -
    every NODE invocation on a given runtime worker instance executes inside the literal
    same OS process, with the same filesystem/network access, as every other invocation that
    happens to land on that worker.
  - Both of these match an already-tracked, still-open gap in the master backlog
    (build/runtime sandboxing, currently `MISSING`) - not a new discovery, but this is
    concrete confirmation from reading the actual execution path, not just a checklist item.

**(a) guardrail language - fixed and live-verified.** Added a "Security note" to
`submit_function_version_source`'s description stating plainly that submitted code (both the
build step and the deployed handler) currently runs with real host-level access and is not
sandboxed, and that an agent should not submit code reading credentials/secrets beyond what
an attached Database/Environment already provides, making unexpected outbound network calls,
or performing destructive filesystem operations. This is honest wording matching reality
(mitigation only, not a technical control - see (b) below), verified live through the real
MCP protocol.

**(b) real sandboxing - still open, deliberately not attempted this pass.** Real sandboxing
for both the build step and the actual Node execution step (container-per-build, container-
or-VM-per-invocation, or an equivalent isolation boundary) - this is a substantially larger
piece of work than anything else in this file, effectively its own project rather than a
quick fix, and should be scoped and prioritized separately rather than
folded into the same pass as the documentation-level items above.
