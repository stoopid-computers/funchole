#!/usr/bin/env bash
# Starts ONE tenant sandbox and attaches stdin/stdout to it, speaking the executor's
# newline-delimited JSON protocol. This script is the single source of truth for how a
# sandbox is locked down; the runtime's Java driver and the attack suite both call it.
#
#   launch.sh <name>
#
# Configuration (environment), all optional:
#   SANDBOX_IMAGE            image to run                          (funchole-sandbox:dev)
#   SANDBOX_RUNTIME          OCI runtime, e.g. runsc for gVisor    (docker default)
#   SANDBOX_NETWORK          docker network; "none" = no network   (none)
#   SANDBOX_MEMORY           hard memory limit, swap disabled      (128m)
#   SANDBOX_CPUS             CPU share                             (0.5)
#   SANDBOX_PIDS             max processes                         (64)
#   SANDBOX_TMP_SIZE         writable scratch space                (64m)
#   SANDBOX_ARTIFACT_VOLUME  volume or host dir holding artifacts  (required)
#   SANDBOX_ARTIFACT_MOUNT   where artifacts appear inside        (same as SANDBOX_ARTIFACT_DIR)
#   SANDBOX_ARTIFACT_DIR     path artifacts are addressed by       (/tmp/funchole/artifact-cache)
#   SANDBOX_DNS              comma list of DNS servers. Set this: under gVisor Docker's embedded resolver
#                            (127.0.0.11) does not work, and the egress guard only allows listed servers. The
#                            sandbox then gets its own read-only /etc/resolv.conf instead of Docker's.
#   SANDBOX_STATE_DIR        required with SANDBOX_DNS: a directory the Docker daemon can bind-mount at the
#                            SAME path (like the artifact volume), where resolv.conf files are written
#   SANDBOX_ADD_HOSTS        comma list of name:ip entries for /etc/hosts, e.g. tenant-db:10.213.40.10
#                            (needed for the same reason: container names do not resolve under gVisor)
#
# Nothing from the caller's environment is passed into the container: the sandbox holds no
# platform secret because it is never handed one.
set -euo pipefail

name="${1:?usage: launch.sh <container-name>}"
artifact_dir="${SANDBOX_ARTIFACT_DIR:-/tmp/funchole/artifact-cache}"
mount_target="${SANDBOX_ARTIFACT_MOUNT:-$artifact_dir}"
volume="${SANDBOX_ARTIFACT_VOLUME:?SANDBOX_ARTIFACT_VOLUME is required}"

args=(
  run --rm -i --init
  --name "$name"
  --label funchole.sandbox=1
  --network "${SANDBOX_NETWORK:-none}"
  --read-only
  --tmpfs "/tmp:rw,noexec,nosuid,nodev,size=${SANDBOX_TMP_SIZE:-64m}"
  --cap-drop ALL
  --security-opt no-new-privileges
  --user 10001:10001
  --memory "${SANDBOX_MEMORY:-128m}" --memory-swap "${SANDBOX_MEMORY:-128m}"
  --cpus "${SANDBOX_CPUS:-0.5}"
  --pids-limit "${SANDBOX_PIDS:-64}"
  --ulimit nofile=512:512
  -v "${volume}:${mount_target}:ro"
)
[[ -n "${SANDBOX_RUNTIME:-}" ]] && args+=(--runtime "$SANDBOX_RUNTIME")
if [[ -n "${SANDBOX_DNS:-}" ]]; then
  # --dns would only set the UPSTREAM of Docker's embedded resolver on a user-defined network, and that
  # resolver is unreachable under gVisor. Give the sandbox its own resolv.conf (content-addressed, so
  # concurrent launches write identical files).
  state="${SANDBOX_STATE_DIR:?SANDBOX_STATE_DIR is required when SANDBOX_DNS is set}"
  mkdir -p "$state"
  resolv="$state/resolv-$(echo "$SANDBOX_DNS" | tr -c 'A-Za-z0-9.' '_').conf"
  { for dns in $(echo "$SANDBOX_DNS" | tr ',' ' '); do echo "nameserver $dns"; done; echo "options timeout:2 attempts:2"; } > "$resolv.$$"
  chmod 644 "$resolv.$$" && mv "$resolv.$$" "$resolv"
  args+=(-v "$resolv:/etc/resolv.conf:ro")
fi
for host in $(echo "${SANDBOX_ADD_HOSTS:-}" | tr ',' ' '); do args+=(--add-host "$host"); done

exec docker "${args[@]}" "${SANDBOX_IMAGE:-funchole-sandbox:dev}"
