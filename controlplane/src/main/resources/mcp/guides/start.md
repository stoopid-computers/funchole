# Build and ship an app

You assemble existing building blocks. Users describe the app; you handle platform vocabulary and tool ordering. Read only the guides required by the task. Choose a starting plan here before creating resources.

- STATIC: one complete site artifact, a GET wildcard route, and real HTTPS page/asset checks.
- DYNAMIC: a NODE HTTP handler, a pinned NODE Flow, draft invocation, and real HTTP contract checks. Add supplied Postgres only when persistence is needed.
- MULTIPLAYER: STATIC UI plus authenticated NODE APIs, supplied Postgres, transactions, and bounded HTTP polling. Test two independent sessions and persistence. Ask before substituting polling for requested realtime behavior.

## Choose the building blocks

| Need | Building block | Read before authoring |
| --- | --- | --- |
| Website, SPA, browser assets | One STATIC Function for a complete site | funchole://guides/static |
| JSON API or business logic | NODE Function, ending a Flow with RESPONSE | funchole://guides/node |
| HTTP URLs or reusable composition | Gateway, Flow Route, Flow Version with pinned steps | funchole://guides/flows |
| Stored app data, configuration, secrets | Supplied external Postgres, shared EnvironmentProfile | funchole://guides/data |
| Shared multiplayer state | NODE API, Postgres transactions, browser polling | funchole://guides/multiplayer |
| Build/runtime/HTTP failure | Logs and a fix-forward revision | funchole://guides/troubleshooting |
| Extend an existing app or retain a proven procedure | Reuse components, inspect source, project-local notes | funchole://guides/evolve |

The current public tools are `discover`, `read`, `build_function`, `compose_flow`, `invoke`, `publish_flow`, `configure`, `connect_database`, `configure_gateway`, `claim_domain`, and `retire`. Tool boundaries follow intent; the count is not a platform constraint.

Mutating calls accept optional top-level `clientOperationId`. Keep the same ID and arguments when a response is lost; a completed receipt is replayed without repeating the mutation. Changed arguments or uncertain in-progress operations conflict. Inspect surviving state before deciding how to continue. Receipts include structured `nextActions`, and paged discovery/reads include `continuation.nextArguments` for the next call.

`discover` defaults to `scope="knowledge"`; optional `query` searches guidance and contracts only in that scope. Follow each returned pointer with `read(reference)`. `read("funchole://tools/build_function")` returns the actual tool schema in receipt `data`. `read("funchole://guides/start")` returns the same Markdown string as native `resources/read`.

Every tool returns a receipt with `ok`, `code`, `message`, `reference`, `data`, `links`, and `warnings`. Check `ok` before using `data`; retain returned references rather than reconstructing IDs. Failure receipts can describe partial work. Inspect state before retrying mutations.

## Discover before creating

Use `discover` scopes `functions`, `flows`, `gateways`, `domains`, `custom-domains`, `databases`, and `environments`. For `function-versions` or `flow-versions`, pass the parent identity reference. Discovery returns `data={items,total,nextOffset}`; inventory items contain `reference` and `summary`, while knowledge items contain `kind`, `name`, `description`, and `pointer`. Set `limit` no higher than 20 and continue using `nextOffset` until it is null. Reuse suitable resources. Never infer global absence from one page.

A Gateway needs a verified base domain and usable DNS/TLS. Reuse a hosted default Gateway when available; otherwise read the `configure_gateway` and `claim_domain` contracts for permitted setup. If prerequisites are missing, ask for the specific missing access or input. Hosted signup can provision a default Database, so inspect existing resources first. `connect_database` registers an existing Postgres connection rather than provisioning a server. Request missing credentials through a secure workflow.

## Build, test, publish

1. `read` the chosen `funchole://examples/{SCENARIO}` fixture. Adapt its files, retaining the contract.
2. `build_function(request)` with new `key`, `name`, `runtime`, `entrypoint`, optional `handler`, complete `files` entries with `path`/`content`, and database references in `databases`. For updates pass `functionRef` and an explicit `baseVersionRef`; omitted env/secrets inherit from that base.
3. Keep complete source locally when permitted. A file list replaces source; omission is not a patch.
4. Poll `read` on the returned `funchole://function-versions/{functionId}/{id}` reference. Continue only when `data.status` is READY. On FAILED use `view="logs"` and read the troubleshooting guide. Bound polling and report timeout.
5. `compose_flow(request)` with the route and matching runtime, pinning a READY Function Version via `componentRef` or explicit `steps`. Retain the returned Flow Version reference. See funchole://guides/flows.
6. Test a draft NODE Flow with `invoke(reference, input)` using a raw JSON string, then poll `read` on the returned Invocation reference. Inspect results and logs. STATIC is tested over HTTP after publication.
7. Confirm live-traffic changes fit the user's request, then `publish_flow(request)` with the Flow Version `reference` and explicit `expectedActiveVersionRef: null` initially, or the exact active version reference for that same Flow on updates. Omission and the string `"none"` are invalid.
8. Check the real HTTPS URL with the host's HTTP/browser tools. Test nested pages/assets for STATIC, and status/body/headers/cookies/invalid inputs for NODE. Direct Invocation success does not verify DNS, TLS, routing, or browser behavior.

Done means a tested URL and observed behavior, not merely an ADOPTED row. Report checked URLs, identifiers needed to resume, and remaining blockers. If the host has no HTTP/browser access, state that live HTTP verification is still pending.

## Safety boundary

Submitted builds and NODE handlers currently execute with host-level access without a sandbox. Submit only trusted application code. Use attached resources for app secrets; keep secret values out of source, guides, logs, browser bundles, and project notes. Request approval for destructive data changes or new external infrastructure beyond the user's request. Documentation is guidance, not execution isolation.
