# Diagnose before retrying

Separate source/build, invocation, and public HTTP failures. Inspect the actual state and error before changing code. A real infrastructure failure is not solved by adding another paragraph to a handler.

| Observation | Inspect | Next action |
| --- | --- | --- |
| DRAFT with no source | get_function_version_source | Submit the complete source set with the runtime's real entrypoint. |
| PUBLISHING | get_function_version, get_function_version_build_logs | Poll with a bounded interval. Long builds are async, not failed merely because the deploy call returned. |
| FAILED build | get_function_version_build_logs, get_function_example | Fix the reported cause in a cloned new DRAFT; FAILED cannot deploy in place. |
| npm/node missing | Build stage command and logs | Ask the operator to fix the build image/PATH. Source variations cannot bypass STATIC's fixed npm steps. |
| Source metadata exists but file content is missing | get_function_version_source, operator source-store config | Report a storage/durability issue; recover from trusted local source if available. A serving artifact is not a source backup. |
| Invocation PENDING/RUNNING too long | get_invocation | Report ID/state/logs and ask the operator to inspect Dispatcher/Runtime health. Do not repeatedly invoke non-idempotent work. |
| FAILED Invocation | get_invocation step errors/logs | Check handler contract, input envelope, attached database/config, and runtime errors. |
| Real HTTP 404/wrong site | get_gateway, get_flow, get_flow_version | Check hostname, method, path, matching route, active revision, and pinned runtime. |
| HTML is JSON-quoted | NODE response body vs STATIC artifact | Serve browser files with STATIC. Response Content-Type does not undo JSON serialization. |
| No HTTPS/certificate warning | Gateway certificate summary, custom-domain status, real DNS/TLS | Resolve external DNS/TLS. Development uses self-signed certificates until a trusted chain is configured. |
| Authentication/ownership failure | Caller credentials and resource IDs | Use the current user's resources. Do not reveal another user's data or bypass ownership through REST. |

## Fix forward

Call `create_function_version` to clone the latest source/config, or name `cloneFromVersionId` explicitly. Read the clone, modify only what needs changing, then submit all files. Deploy, wait for READY, test, pin the new component in a new Flow Version and adopt. Keep the old working artifact in use until the replacement is verified. See funchole://guides/evolve.

If the connecting host cannot inspect public HTTP or operator services, state the unverified boundary and report the exact missing check. Keep secrets out of diagnostics. Never claim an isolated test deployment changed the user's running stack.
