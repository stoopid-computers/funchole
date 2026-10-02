# Build and ship an app

You assemble existing building blocks. Users describe the app; you handle platform vocabulary and tool ordering. Read only the guides required by the task. Call `plan_application` for a STATIC, DYNAMIC, or MULTIPLAYER starting plan. It is read-only, not a deploy operation.

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

`search_funchole(query)` finds guides and real tools, including parameter names. `get_funchole_tool(name)` returns one exact schema. `get_funchole_guide(topic)` returns the same body as `resources/read` for hosts without resource support.

## Discover before creating

List existing Gateways, Functions, Flows, databases, and environments. Lists are paginated, normally 20 entries; continue while a full page is returned. Reuse a suitable deployed component. Never infer global absence from one page.

A Gateway needs a verified base domain and usable DNS/TLS. Admin users provide a verified AppDomain ID; hosted non-admin users may already have a default Gateway or may omit that ID. If prerequisites are missing, ask for the specific missing access or input. Hosted signup can provision a default Database, so inspect existing resources first. MCP's `create_database` registers an existing Postgres connection rather than provisioning a server. Request missing credentials through a secure workflow.

## Build, test, publish

1. Fetch `get_function_example` for the chosen runtime/contract. Adapt the files to the app, retaining the contract.
2. `create_function`, then `create_function_version`. Set configuration and attach databases as needed.
3. `submit_function_version_source` with the complete file list. Submission replaces the whole set. Keep local source when the host supports file writes.
4. `deploy_function_version`, then poll `get_function_version`. Continue only at READY; on FAILED read build logs and the troubleshooting guide. Use a bounded polling interval and stop/report if the build never reaches a terminal state.
5. Create a Flow Route and matching runtime Flow Version. Pin READY Function Versions in its steps. See funchole://guides/flows.
6. Test a draft NODE Flow with `invoke_flow_version`, then poll `get_invocation`. Inspect the actual result and logs. STATIC is tested over HTTP after adoption.
7. Confirm live-traffic changes fit the user's request, then `adopt_flow_version`.
8. Check the real HTTPS URL with the host's HTTP/browser tools. Test nested pages/assets for STATIC, and status/body/headers/cookies/invalid inputs for NODE. Direct Invocation success does not verify DNS, TLS, routing, or browser behavior.

Done means a tested URL and observed behavior, not merely an ADOPTED row. Report checked URLs, identifiers needed to resume, and remaining blockers. If the host has no HTTP/browser access, state that live HTTP verification is still pending.

## Safety boundary

Submitted builds and NODE handlers currently execute with host-level access without a sandbox. Submit only trusted application code. Use attached resources for app secrets; keep secret values out of source, guides, logs, browser bundles, and project notes. Request approval for destructive data changes or new external infrastructure beyond the user's request. Documentation is guidance, not execution isolation.
