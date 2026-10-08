#!/usr/bin/env bash
# Runs the untrusted-code attack suite and the golden-behaviour checks
# (docs/SANDBOX_ISOLATION_PRD.md, E1/E2).
#
#   scripts/attack-suite.sh legacy       today's shared Node process: expected to FAIL (exit 1)
#   scripts/attack-suite.sh sandbox      real per-tenant containers: must PASS (exit 0). Needs Docker;
#                                        set SANDBOX_RUNTIME=runsc to test under gVisor.
#   scripts/attack-suite.sh golden       golden behaviours on legacy AND sandbox: both must match
#   scripts/attack-suite.sh self-check   proves the suite itself: detects legacy's holes AND passes a secure target
set -euo pipefail
cd "$(dirname "$0")/.."

ensure_image() {
  docker image inspect "${SANDBOX_IMAGE:-funchole-sandbox:dev}" >/dev/null 2>&1 \
    || docker build -q -f docker/sandbox/Dockerfile -t funchole-sandbox:dev . >/dev/null
}

cd runtime
case "${1:-}" in
  self-check)
    node attack-suite/run.mjs --target legacy --expect-detect
    node attack-suite/run.mjs --target deny-all
    ;;
  golden)
    node golden/run.mjs --target legacy
    (cd .. && ensure_image) && node golden/run.mjs --target sandbox
    ;;
  sandbox)
    (cd .. && ensure_image) && node attack-suite/run.mjs --target sandbox
    ;;
  legacy|deny-all)
    node attack-suite/run.mjs --target "$1"
    ;;
  *)
    echo "usage: $0 legacy|sandbox|golden|self-check" >&2
    exit 64
    ;;
esac
