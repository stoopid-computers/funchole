# Diagnose before retrying

Separate source/build, invocation, and public HTTP failures. Inspect the actual state and error before changing code. A real infrastructure failure is not solved by adding another paragraph to a handler.

| Observation | Inspect | Next action |
| --- | --- | --- |
| DRAFT with no source | `read(reference, view="source")`, then selected files | Submit the complete source set with the runtime's real entrypoint. |
| PUBLISHING | `read(reference)` and `read(reference, view="logs")` | Poll with a bounded interval. Long builds are async, not failed merely because the build call returned. |
| FAILED build | `read(reference, view="logs")`, runtime example URI | Fix the reported cause in a new version based on an explicit base reference; FAILED cannot deploy in place. |
| npm/node missing | Build stage command and logs | Ask the operator to fix the build image/PATH. Source variations cannot bypass STATIC's fixed npm steps. |
| Source metadata exists but file content is missing | `read(reference, view="source", file=...)`, operator source-store config | Report a storage/durability issue; recover from trusted local source if available. A serving artifact is not a source backup. |
| Invocation PENDING/RUNNING too long | `read` of Invocation reference | Report reference/state/logs and ask the operator to inspect Dispatcher/Runtime health. Do not repeatedly invoke non-idempotent work. |
| FAILED Invocation | `read` of Invocation step errors/logs | Check handler contract, input envelope, attached database/config, and runtime errors. |
| Real HTTP 404/wrong site | `read` of Gateway, Flow, and Flow Version references | Check hostname, method, path, matching route, active revision, and pinned runtime. |
| HTML is JSON-quoted | NODE response body vs STATIC artifact | Serve browser files with STATIC. Response Content-Type does not undo JSON serialization. |
| No HTTPS/certificate warning | Gateway certificate summary, custom-domain status, real DNS/TLS | Resolve external DNS/TLS. Development uses self-signed certificates until a trusted chain is configured. |
| Authentication/ownership failure | Caller credentials and resource IDs | Use the current user's resources. Do not reveal another user's data or bypass ownership through REST. |

## Fix forward

Read the intended base version and its source files. `build_function` with `functionRef`, explicit `baseVersionRef`, and the complete corrected files. Modify only what needs changing. Wait for READY, test, `compose_flow` with the existing `flowRef`, and `publish_flow` with the exact expected active version reference. Keep the old working artifact in use until the replacement is verified. See funchole://guides/evolve.

Check receipt `ok` even when the MCP result is not marked `isError`. On a conflict or partial failure, inspect the receipt reference and links before retrying. Never print sensitive request bodies or credentials in diagnostics.

If the connecting host cannot inspect public HTTP or operator services, state the unverified boundary and report the exact missing check. Keep secrets out of diagnostics. Never claim an isolated test deployment changed the user's running stack.
