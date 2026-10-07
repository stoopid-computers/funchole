# Compose and publish HTTP routes

A Gateway is the HTTP entry point. A Flow is a logical composition; its Flow Route maps an HTTP method and path under a Gateway. `compose_flow` creates an identity and draft composition together, or a new revision of an existing Flow. A Flow Version pins the composition at one revision.

## Build a route

1. Reuse a Gateway from `discover(scope="gateways")`, or use `configure_gateway` with request `name`, verified `domainRef`, and `status="ACTIVE"`. Read its contract for account-specific prerequisites.
2. `compose_flow` with new request `key`, `name`, `gatewayRef`, `httpMethod`, `path`, `priority`, and matching `runtime`, NODE or STATIC.
3. For one component, pass a single READY Function Version reference as `componentRef`. This creates a terminal RESPONSE for NODE or a FUNCTION step for STATIC.
4. For ordered compositions use `steps` instead, following the exact `funchole://tools/compose_flow` schema. Pin READY Function Versions and ADOPTED sub-Flow Versions. References are returned resource URIs, not unique keys.
5. For NODE, `invoke` the returned `funchole://flow-versions/{flowId}/{id}` reference and inspect the Invocation with `read`. End with RESPONSE or an adopted SUB_FLOW. For STATIC, the Gateway serves the READY artifact without invocation.
6. `publish_flow` with request `reference` and explicit `expectedActiveVersionRef: null` for initial publication, or the exact same-Flow active version reference on updates. It makes the route live and archives the previous adopted revision. A stale expectation is a conflict; inspect state rather than overwriting another update. Check real HTTP afterward.

For an existing Flow, `compose_flow` takes `flowRef` and no route fields. Ordinary publication preserves the route. Supply an explicit `publish_flow.request.route` only when the user requested a route change; read that contract before doing so.

Steps run in position order. FUNCTION and MIDDLEWARE both execute Function code; RESPONSE ends the Invocation. Sub-Flows are resolved into a pinned execution snapshot. There is no built-in branching or retry/backoff just because step metadata mentions it. Implement needed transformations and decisions in NODE code within current execution limits.

## Paths and precedence

An exact path, such as `/api/notes`, matches that path. Colon segments capture one segment, such as `/api/notes/:id` into `input.pathParameters.id`. A single trailing wildcard, such as `/app/*`, owns a subtree. Colon parameters and wildcards cannot be combined. Use one wildcard for a whole STATIC site. Check existing routes and priorities before adding overlapping paths; test each intended match rather than relying on unspecified precedence.

## DNS and TLS

The default Gateway hostname is `<uniqueKey>.<domainName>` from `read` of the Gateway reference. Check the certificate summary before claiming HTTPS is ready. DNS and certificate propagation are external to artifact readiness.

`claim_domain` takes request `kind="BASE"` or `"CUSTOM"`, `hostname`, and `gatewayRef` for a new custom hostname. Retain its receipt reference and returned DNS metadata. For base domains, state includes `id`, `domainName`, and `verificationCode`. For custom domains, follow returned TXT verification details, CNAME target for subdomains, or A-record target for an apex. Publish the requested records with the user's DNS provider, then call `claim_domain` with the existing `reference` and `check=true`. Use `check=true` only for an existing claim. Verification triggers custom-domain certificate issuance; check state and actual HTTPS. Never claim DNS is configured merely because the record was registered in FuncHole.

Retiring live resources can remove traffic or dependencies. Read `funchole://tools/retire` and obtain approval for destructive changes. An archived version cannot simply be re-adopted; compose a new DRAFT with steps pinned to the desired earlier component versions and publish with the expected active revision. For updates and reuse read funchole://guides/evolve. For routing/runtime failures read funchole://guides/troubleshooting.
