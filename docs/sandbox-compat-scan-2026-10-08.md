# Compatibility scan of stored tenant sources (T0.7)

Date: 2026-10-08 · Method: read-only static grep of all sources in the production object store, run in a throwaway container; only counts and matched tokens were collected, nothing was copied off the host. Scope: stored function sources (not built artifacts, not deleted versions). A string search cannot prove absence, so this informs the design; it is not a security audit.

## Size
365 function versions (353 with stored sources), 3,574 files, about 50 MB (mostly tsx/mjs/ts/js/json).

## Results
| Pattern | Versions | Verdict |
|---|---|---|
| `/proc`, `docker.sock`, `/etc/passwd` | 0 | none |
| Listening servers (`createServer`, `.listen(`) | 0 | none |
| Native addons (`binding.gyp`, `node-gyp`, `.node`) | 0 | none, so gVisor native-module risk is currently theoretical |
| npm lifecycle scripts (`preinstall`/`install`/`postinstall`) | 0 | none, so `--ignore-scripts` by default breaks nobody today |
| Platform hosts (`openbao`, `nats://`, `rustfs`, `169.254.x`) | 0 | none, no sign anyone has probed the platform |
| `tenant-db` hostname hard-coded | about 6 matches, 2-3 tenants | **must keep `tenant-db` resolvable and reachable from sandboxes** |
| Child processes | 5 versions, 3 tenants | One real use: a build-time `execSync('tar -xzf bundle.tar.gz')` in an `unpack.mjs` (binary bundle workaround). The rest are false positives (`spawn` game functions, `RegExp.exec`). **The build sandbox must allow child processes and ship `tar`.** |
| `fs` imports | 35 versions, 5 tenants | Mostly build/server helper code. Needs the T2.21 compatibility run to confirm no function relies on persistent local files |
| `process.env.*` | 122 versions | Expected: functions read their own injected variables (API keys, URLs). The sandbox keeps per-invocation env, so this is unaffected |
| "smtp" string | 22 versions, 1 tenant (the owner) | Email goes over an HTTPS API (Resend); no raw SMTP sockets seen |

## Consequences for the PRD
- T1.3 (spawn) is **hardening**, not critical, at runtime; but builds legitimately spawn processes (T4.x).
- T4.2: build sandbox image includes `tar`/`gzip` and permits child processes.
- T3.2/T3.5: DNS and egress allow-list include `tenant-db`.
- T2.21: explicitly test local-file usage (`fs`) and the static-bundle build.
- No evidence of exploitation of the current runtime in stored sources.
