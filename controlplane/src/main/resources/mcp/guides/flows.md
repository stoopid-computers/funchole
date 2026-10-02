# Compose and publish HTTP routes

A Gateway is the HTTP entry point. A Flow is a logical composition; its Flow Route maps an HTTP method and path under a Gateway. The current `create_flow` tool creates that identity and routing mapping together. A Flow Version pins the composition at one revision.

## Build a route

1. Reuse an existing Gateway or create one using `create_gateway`. Admin needs a verified AppDomain ID. Hosted non-admin users can omit that ID. Use ACTIVE when the route should serve traffic.
2. `create_flow` with Gateway ID, HTTP method, route path, and a unique key.
3. `create_flow_version` with the matching runtime, NODE or STATIC. The default is NODE.
4. Add ordered steps with `create_flow_step`. Each Function-backed step needs both the Function ID and a READY Function Version ID. SUB_FLOW needs both the Flow ID and an ADOPTED Flow Version ID. IDs are UUIDs, not unique keys.
5. For NODE, test the draft with `invoke_flow_version` and inspect `get_invocation`. End with RESPONSE or an adopted SUB_FLOW. For STATIC, use a FUNCTION step pointing to the READY STATIC artifact; the Gateway serves files without invocation.
6. `adopt_flow_version` makes the route live and archives the previous adopted revision. It changes live traffic. Check real HTTP afterward.

Steps run in position order. FUNCTION and MIDDLEWARE both execute Function code; RESPONSE ends the Invocation. Sub-Flows are resolved into a pinned execution snapshot. There is no built-in branching or retry/backoff just because step metadata mentions it. Implement needed transformations and decisions in NODE code within current execution limits.

## Paths and precedence

An exact path, such as `/api/notes`, matches that path. Colon segments capture one segment, such as `/api/notes/:id` into `input.pathParameters.id`. A single trailing wildcard, such as `/app/*`, owns a subtree. Colon parameters and wildcards cannot be combined. Use one wildcard for a whole STATIC site. Check existing routes and priorities before adding overlapping paths; test each intended match rather than relying on unspecified precedence.

## DNS and TLS

The default Gateway hostname is `<uniqueKey>.<domainName>` from `get_gateway`. Check the certificate summary before claiming HTTPS is ready. DNS and certificate propagation are external to artifact readiness.

For a user-owned hostname, `attach_custom_domain` returns TXT verification details, a CNAME target for subdomains, and an A-record target for an apex. Publish the requested records with the user's DNS provider, then call `verify_custom_domain`. Verification triggers certificate issuance; check status and actual HTTPS. Never claim DNS is configured merely because the record was registered in FuncHole.

Archiving the live Flow Version removes it from traffic. An archived version cannot simply be re-adopted; create a new DRAFT with steps pinned to the desired earlier component versions and adopt that revision. For updates and reuse read funchole://guides/evolve. For routing/runtime failures read funchole://guides/troubleshooting.
