#!/usr/bin/env bash
# Runs ONE build command in a locked-down container with a job's workspace mounted read-write.
# The single source of truth for how a build is isolated; called by the sandbox manager.
#
#   build-launch.sh <container-name> <workspace-dir> -- <command> [args...]
#
# Configuration (environment), all optional:
#   BUILD_IMAGE     image                                   (funchole-build-sandbox:dev)
#   BUILD_RUNTIME   OCI runtime, e.g. runsc for gVisor      (docker default)
#   BUILD_NETWORK   docker network (needs the npm registry) (bridge)
#   BUILD_MEMORY    hard memory limit, swap disabled        (1g)
#   BUILD_CPUS      CPU share                               (1)
#   BUILD_PIDS      max processes                           (512)
#   BUILD_TMP_SIZE  writable scratch (npm cache, bundlers)  (1g)
#   BUILD_USER      uid:gid to run as                       (the caller's own uid:gid)
#   BUILD_DNS       comma list of DNS servers. Set this: under gVisor Docker's embedded resolver does not
#                   work, and the egress guard only allows listed servers. The build gets its own read-only
#                   /etc/resolv.conf, written next to the workspace (so it lives and dies with the job).
#
# Nothing from the caller's environment enters the container. By default it runs as the CALLER's
# uid/gid so the files it writes stay owned by the manager. A manager running as root (to reach the
# Docker socket) must set BUILD_USER to an unprivileged id, never run builds as root.
set -euo pipefail

name="${1:?usage: build-launch.sh <name> <workspace> -- <command...>}"
workspace="${2:?workspace directory required}"
shift 2
[[ "${1:-}" == "--" ]] && shift
[[ $# -gt 0 ]] || { echo "no command given" >&2; exit 64; }
user="${BUILD_USER:-$(id -u):$(id -g)}"
[[ "${user%%:*}" != "0" ]] || { echo "refusing to run a build as root (set BUILD_USER)" >&2; exit 64; }

args=(
  run --rm --init
  --name "$name"
  --label funchole.sandbox=build
  --network "${BUILD_NETWORK:-bridge}"
  --read-only
  --tmpfs "/tmp:rw,nosuid,nodev,size=${BUILD_TMP_SIZE:-1g}"
  --cap-drop ALL
  --security-opt no-new-privileges
  --user "${BUILD_USER:-$(id -u):$(id -g)}"
  --memory "${BUILD_MEMORY:-1g}" --memory-swap "${BUILD_MEMORY:-1g}"
  --cpus "${BUILD_CPUS:-1}"
  --pids-limit "${BUILD_PIDS:-512}"
  --ulimit nofile=4096:4096
  -e CI=true
  -v "${workspace}:/work:rw"
  -w /work
)
[[ -n "${BUILD_RUNTIME:-}" ]] && args+=(--runtime "$BUILD_RUNTIME")
if [[ -n "${BUILD_DNS:-}" ]]; then
  resolv="$(dirname "$workspace")/resolv.conf"
  { for dns in $(echo "$BUILD_DNS" | tr ',' ' '); do echo "nameserver $dns"; done; echo "options timeout:2 attempts:2"; } > "$resolv"
  chmod 644 "$resolv"
  args+=(-v "$resolv:/etc/resolv.conf:ro")
fi

exec docker "${args[@]}" "${BUILD_IMAGE:-funchole-build-sandbox:dev}" "$@" </dev/null
