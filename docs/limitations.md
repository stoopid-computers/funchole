# Known limitations

## Gateway is single-instance only

Run exactly **one** Gateway container. This is a hard requirement, not a tuning
recommendation - the items below aren't scaling headroom you're leaving on the table, they're
reasons a second replica would behave incorrectly:

- `PendingInvocationResponseRegistry` (the in-memory map correlating an HTTP request with its
  eventual Invocation result) lives entirely inside one Gateway process. A second replica
  would have its own, disjoint copy - an HTTP request handled by replica A whose result
  arrives via replica B's NATS subscription would simply never get a response, not fail over.
- A Gateway restart or redeploy drops every in-flight HTTP request that was mid-flight at that
  moment. The underlying Invocation still runs to completion in the database - nothing about
  the Flow execution itself is lost - but the HTTP client that was waiting on it gets a
  connection drop instead of a response. There is currently no way to avoid this other than
  not restarting Gateway while it's serving traffic you care about.
- If you place a reverse proxy or load balancer in front of Gateway (for TLS-terminated
  logging, rate limiting, etc.), it must route to a single Gateway backend, never round-robin
  or otherwise load-balance across more than one - see the two points above for why that would
  silently drop responses rather than error clearly.

This is an accepted, deliberate constraint for a single-VPS deployment (the target this
project's own deployment tooling assumes), not an oversight to work around by running two
containers and hoping for the best. Horizontal scaling would require making the pending-
response correlation durable and shared (e.g. Postgres- or NATS-KV-backed instead of an
in-process map) - tracked as future work, not attempted in the current architecture.

## Other known limitations

- Build subprocesses and NODE handlers share host access. Tenant ownership checks in the Control Plane do not isolate filesystem access, network egress, host credentials, memory, CPU, or process creation. A multi-tenant cloud release requires enforced build/runtime isolation, resource budgets, filesystem boundaries, egress policy, and secret delivery boundaries. These are separate platform work, not guarantees provided by the guided MCP tools.
- MCP's per-user rate limiter is in-memory per Control Plane process. It is a protective request limit, not a replicated SaaS quota or billing model. Replicated deployments need shared quota accounting.
- MCP source submission fully buffers an HTTP request bounded at 8 MiB. Large source workflows need a tenant-owned blob/upload reference with size and ownership validation before this limit changes.
- Flow publication serializes writers for one Flow. Multi-Flow releases and an independent monotonic publication revision are not implemented. Active-version comparisons do not replace a future publication revision contract.

- Gateway terminal delivery currently uses a core NATS subscription and has no durable per-Gateway redelivery.
- There is currently a registration race between Invocation creation/READY publication and Gateway pending-response registration; a very fast Invocation may complete before its pending HTTP correlation is registered.
- Invocation state transition and JetStream terminal-event publication are not atomic. A terminal DB update may succeed while event publication fails.
- Runtime completion transport failure after ACCEPTED can currently leave a StepExecution RUNNING and runtime capacity reserved.
- Gateway Invocation persistence/lookups currently perform blocking JDBC work on Netty request/event-loop paths and should be moved to worker executors before production concurrency.
- An unsupported next component type currently stops progression while leaving the Invocation PENDING instead of failing it explicitly.
- FUNCTION and RESPONSE failures have no retry/backoff yet; the first execution failure is terminal.
