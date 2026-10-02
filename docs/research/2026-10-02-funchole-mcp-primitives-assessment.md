# FuncHole MCP interface assessment

Research date: 2026-10-02. FuncHole snapshot: `fade1ce`. Pi snapshot: `7fbbd5f4a1d982bb02d63472dde0774fa639f99b`.

This assesses the 75-tool implementation in PR #18. It proposes a replacement interface, not changes to the platform's domain model, REST controllers, database model, builders, Gateway or Dispatcher. The [earlier research](2026-10-02-funchole-mcp-current-state.md) describes the original 71-tool baseline.

## What went wrong

The implementation preserved each REST-shaped tool and added four discovery/planning tools. Several classes explicitly describe themselves as REST mirrors, including `FunctionVersionMcpTools.java:33-40`, `FlowVersionMcpTools.java:25-28`, `GatewayMcpTools.java:21-24`, and `FlowConfigurationMcpTools.java:12-16` under `controlplane/src/main/java/com/funchole/backend/controlplane/mcp/`.

A scan of production `@McpTool` declarations finds 75 names, all unique:

| Group | Tools |
| --- | ---: |
| Function identity | 5 |
| Function Versions, source, builds and configuration | 13 |
| Flow identity and Flow Routes | 5 |
| Flow Versions and steps | 11 |
| Flow resource attachments | 6 |
| Gateway | 5 |
| Base domains | 4 |
| Custom domains | 4 |
| Database | 6 |
| EnvironmentProfile | 8 |
| Invocation | 3 |
| Tested examples | 1 |
| Discovery and guide lookup | 3 |
| Application planning | 1 |

Of these, 30 are `list_` or `get_` tools. Another 15 are `create_` or `update_` tools, eight attach or detach resources, and four set individual configuration entries. Naming is not proof of bad design. The ordering requirements are the stronger evidence.

The live verification script repeats an eight-mutation sequence for a one-step STATIC or NODE Flow, plus an attachment call when a Database is needed and repeated status reads. The sequence is Function creation, Function Version creation, source submission, build, Flow creation, Flow Version creation, step creation, adoption. See [`scripts/orbstack/verify.py:51-81`](../../scripts/orbstack/verify.py). That sequence exposes bookkeeping a server can perform. It should not require eight independently learned mutation contracts.

The discovery tests deliberately assert 75 schemas and preservation of old names. See [`McpGuidanceTests.java:123-147`](../../controlplane/src/test/java/com/funchole/backend/controlplane/mcp/McpGuidanceTests.java). Those assertions protected compatibility, not interface quality.

## What a deeper interface can hide

### Build one Function Version

An MCP module can combine owned Function resolution, draft creation from an explicit base revision, a complete source submission, configuration/Database attachments and asynchronous build submission. Existing ownership-checked services already perform those operations. The caller supplies source and intent, and receives durable Function and Function Version references and a status pointer.

The module must preserve the important facts:

- Source submission replaces the complete file set. Omitted files disappear. Source becomes immutable once deployment starts. [`FunctionVersionSourceService.java:66-110`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/FunctionVersionSourceService.java).
- Draft creation normally clones the latest revision, which might be a failed experiment rather than the live version. An update should identify its intended base explicitly. [`FunctionVersionCloneService.java:48-80`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/FunctionVersionCloneService.java).
- Build submission returns PUBLISHING. READY and FAILED are observed afterward. FAILED is terminal for that revision. [`FunctionVersionDeploymentService.java:98-124`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/FunctionVersionDeploymentService.java).
- SourceStore and ArtifactPublisher writes cross the database transaction seam. Calling several services inside one transaction does not make object storage atomic. [`FunctionVersionSourceService.java:88-94`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/FunctionVersionSourceService.java), [`FunctionVersionDeploymentService.java:135-172`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/FunctionVersionDeploymentService.java).

This is workflow compression. A tool accepting the old operation name plus arbitrary arguments is only a dispatcher and does not remove these ordering rules.

### Compose one Flow Version

A complete ordered step list can replace caller-managed step positions and repeated step CRUD. One-step NODE composition should default to a terminal RESPONSE step. One-step STATIC composition should use FUNCTION. Explicit composition must retain MIDDLEWARE, FUNCTION, RESPONSE and SUB_FLOW references and pin exact component revisions.

The existing adoption validator checks referenced components again, checks ordering, and requires an appropriate terminal step. See [`FlowVersionService.java:83-159`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/FlowVersionService.java). The new MCP composition module should validate those conditions before reporting that a draft is ready for testing, while still retaining the service's adoption checks.

### Invoke and inspect

Function and Flow invocation share the same asynchronous Invocation lifecycle and inspection model. A typed target reference can replace two invocation tools. Inspection can retrieve state, per-step results and logs without separately teaching the caller parent and child UUID pairs. See [`InvocationMcpTools.java:55-99`](../../controlplane/src/main/java/com/funchole/backend/controlplane/mcp/InvocationMcpTools.java).

Invocation can write application data or make external calls. It is not a read-only operation merely because an agent is testing. Direct invocation does not test DNS, TLS, Gateway routing, HTTP cookies or browser behavior.

## Facts the MCP must not hide

### A draft does not stage every setting

Flow Route fields belong to the Flow identity and update immediately through `FlowService.updateFlow`. Database and EnvironmentProfile attachments also belong to the Flow, not its versions. See [`FlowService.java:75-90`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/FlowService.java) and [`FlowConfigurationService.java:51-104`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/FlowConfigurationService.java).

Shared profile values and Database connection details are mutable independently of Flow adoption. See [`EnvironmentProfileService.java:94-126`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/EnvironmentProfileService.java) and [`DatabaseService.java:89-107`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/DatabaseService.java).

Consequences for the proposed interface:

- Preparing a replacement Flow Version must leave the live Flow Route and shared attachments unchanged.
- Changing shared configuration needs an explicit live-impact contract. Cloning a new Function Version is not enough to isolate it.
- An MCP-only redesign can reject unsupported staging combinations or introduce narrowly scoped orchestration over existing services. It cannot claim versioned Flow bindings, a durable app-level release, or multi-route atomicity that the model does not provide.
- A draft tested with the old Flow bindings does not prove that proposed new bindings work.

### Secret reads differ from ordinary inspection

There is an intentional, explicit database password reveal operation. It is separate from listing and inspection in both the MCP and service. See [`DatabaseMcpTools.java:55-63`](../../controlplane/src/main/java/com/funchole/backend/controlplane/mcp/DatabaseMcpTools.java) and [`DatabaseService.java:50-59`](../../controlplane/src/main/java/com/funchole/backend/controlplane/service/DatabaseService.java). A consolidated read tool must not absorb this behavior. Omitting the reveal operation from the new MCP would be a deliberate capability change that needs agreement, not an accidental simplification.

### Sanitizing errors can erase guidance

The modern adapter replaces callback errors with one generic troubleshooting message. See [`ModernMcpProtocol.java:94-119`](../../controlplane/src/main/java/com/funchole/backend/controlplane/mcp/ModernMcpProtocol.java). That avoids raw exception disclosure, but a smaller interface needs safe structured outcomes for known failures, such as missing prerequisites, a non-READY reference, a conflicting live revision and a failed build. Otherwise agents must guess which read to perform next.

## Pi requirements to preserve

Pi's small built-in tool set and its lazy knowledge loading solve different problems. It defaults to `read`, `bash`, `edit` and `write`, while keeping documentation as paths and topic pointers. See `../pi/packages/coding-agent/src/core/system-prompt.ts:55-59,146-168`.

Pi can defer a large MCP catalog client-side. That does not make a complicated server interface simpler for an agent after discovery. Its MCP exposure modes and resource support are described at `../pi/packages/coding-agent/docs/mcp.md:167-224`. It also avoids retrying tool calls because the mutation may already have happened, at lines 224-225.

The replacement must retain these mechanisms:

1. Stable short instructions identify the server and a start-guide pointer.
2. Bounded discovery returns routing descriptions and exact pointers, not all documentation bodies.
3. One read path loads a guide, tested example or contract on demand. Native MCP resources and the tool fallback return the same content.
4. Operation results carry readable status/log pointers and a concrete next action, including recovery after a failed build.
5. Durable domain references allow a new conversation to resume without relying on transport sessions or an in-memory plan token.
6. Reusable Function Versions and Flow Versions remain expressible, rather than forcing one monolithic Function per app.
7. Verified procedures can become project-local skills or runbooks through the host's permitted file tools. Shared server contracts remain reviewed code, and tenant notes cannot weaken authorization.

Pi's extension ladder distinguishes instructions, prompts, skills and executable extensions. See `../pi/packages/coding-agent/docs/quickstart.md:94-106`. FuncHole should document what the connecting host can author rather than claim that an MCP server can modify the host's memory or gain new privileges. The existing [`evolve` guide](../../controlplane/src/main/resources/mcp/guides/evolve.md) already makes that distinction.

## Design tests

Judge the replacement by the number of concepts, required parameters, lifecycle rules, and calls needed for a task, as well as tool count and serialized schema bytes. A small catalog of overloaded tools can still fail all those tests.

Use these journeys before accepting the interface:

- A multi-page STATIC site with nested assets.
- A NODE API with supplied Postgres and a STATIC frontend.
- A two-session persisted multiplayer app using HTTP polling.
- A fix-forward update based on the live source while a newer failed revision exists.
- Two Flows sharing an exact Function Version, followed by updating only one of them.
- Composition with middleware and a reusable SUB_FLOW.
- A self-hosted account missing a verified base domain or Gateway.
- A custom-domain claim awaiting external DNS and then certificate readiness.
- Failed source storage, secret storage, build submission and asynchronous builds, with honest partial-state reporting.
- A stale publish attempt and a timeout after a mutation, without an automatic blind retry.
- A tools-only host and a resource-aware host discovering the same guidance.
- A fresh conversation using a project-local runbook and stable domain references.

These are proposed acceptance scenarios, not evidence that the replacement has been implemented or passed tests.
