#!/usr/bin/env bash
# One command to run the whole backend test suite with the infrastructure it needs.
#
#   scripts/test-all.sh                 start Postgres + NATS, run every module's tests, stop them
#   scripts/test-all.sh --tests '*Foo'  pass extra args through to Gradle
#
# Needs only Docker and a JDK: Postgres, NATS, an S3 store (rustfs) and a dev OpenBao are started for you.
#
# Known limit: two tests poll an invocation until a real dispatcher + runtime finish it
# (FlowVersionInvocationIntegrationTests.invokesADraftFlowVersionWithoutRequiringAdoption and
# FunctionVersionInvocationIntegrationTests.persistsAndSurfacesRuntimeConsoleOutputThroughInspection).
# They pass against the full stack (`docker compose -f docker-compose.dev.yml up`) and fail here.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPOSE=(docker compose -f docker-compose.test.yml)
trap '"${COMPOSE[@]}" down -v >/dev/null 2>&1 || true' EXIT

"${COMPOSE[@]}" up -d --wait
# The bucket must exist before tests start: wait for the one-shot init container to finish.
init_id="$("${COMPOSE[@]}" ps -aq rustfs-init)"
until [ "$(docker inspect -f '{{.State.Status}}' "$init_id")" = "exited" ]; do sleep 1; done
[ "$(docker inspect -f '{{.State.ExitCode}}' "$init_id")" = "0" ] || { echo "rustfs-init failed" >&2; exit 1; }

export DB_URL="jdbc:postgresql://localhost:55432/funchole"
export DB_USERNAME=funchole DB_PASSWORD=funchole
export NATS_URL="nats://localhost:54222"
export S3_ARTIFACT_ENDPOINT="http://localhost:59000"
export BAO_ADDR="http://localhost:58200" BAO_TOKEN=root

./gradlew test --offline --continue "$@"
