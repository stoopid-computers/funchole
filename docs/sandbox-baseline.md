# Baseline before sandboxing (E0)

Recorded 2026-10-08 on a developer laptop (Apple silicon, Node 20). Re-measure on the production VPS before comparing there; these numbers are for relative comparison only.

## How to reproduce
| What | Command |
|---|---|
| Whole backend suite with its infrastructure | `scripts/test-all.sh` |
| Golden behaviours of the Node runtime layer | `node runtime/golden/run.mjs --target legacy` |
| Performance of the Node runtime layer | `node runtime/golden/bench.mjs --target legacy` |
| Attack suite (must fail on legacy) | `scripts/attack-suite.sh legacy`, and `scripts/attack-suite.sh self-check` |
| Real sandbox and golden checks (Docker) | `scripts/attack-suite.sh sandbox`, `scripts/attack-suite.sh golden` |
| Network guard against real Docker networks | `scripts/egress-test.sh` (edits and then cleans the Docker host's firewall) |

`--target sandbox` is the same command once the sandbox target exists (E2); outputs must match exactly.

## Backend suite (T0.2/T0.3)
`scripts/test-all.sh` starts Postgres, NATS, rustfs (S3) and a dev OpenBao from `docker-compose.test.yml` on non-conflicting ports and runs every module. Before: 88 of 266 controlplane tests failed on a fresh machine for environmental reasons. Now: **264 of 266 pass**. The two that fail poll an invocation until a real dispatcher and runtime complete it, so they need the full dev stack (`docker compose -f docker-compose.dev.yml up`) and pass there.

## Golden behaviours (T0.6)
13 behaviours, all matching on `legacy`: result passthrough, request shape (headers/cookie/query), per-invocation env and no leakage into the next call, secret redaction in logs, stdout/stderr capture, error codes for thrown errors, unserializable output, missing handler and missing artifact, a 1 MB body, async awaiting, and the `context.db()` error shape.

## Performance (T0.5), Node runtime layer
| Metric | Legacy |
|---|---|
| Cold start (spawn + first call) | 60–80 ms |
| Warm echo p50 / p95 / p99 | 0.06–0.08 ms / 0.15–0.17 ms / 0.33–0.42 ms |
| Warm 256 KB payload p50 / p95 | 2.6 ms / 3.8 ms |
| Executor resident memory | about 60 MB |

Budget proposal for the sandbox (to confirm): warm p95 within +5 ms of legacy at the runtime layer; first call to an idle tenant under 500 ms; at most 128 MB per idle sandbox.
