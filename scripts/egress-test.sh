#!/usr/bin/env bash
# Live test of the egress guard (docs/SANDBOX_ISOLATION_PRD.md, E3) against real Docker networks.
#
# Builds an isolated sandbox network, starts the guard on the Docker host's network namespace, starts
# canary services that a hostile function would love to reach, then runs the attack suite from INSIDE a
# real sandbox container on that network. Also proves the rules persist when the guard stops.
#
#   scripts/egress-test.sh          needs Docker, internet access, and the sandbox + guard images
#
# It edits the iptables of the Docker host (the Docker Desktop VM on a Mac) and removes its rules at the end.
set -euo pipefail
cd "$(dirname "$0")/.."

NET=fh-egress-test; OTHER=fh-egress-other; BR=fhegr0
# A private range unlikely to clash with a developer machine's existing Docker networks (checked below).
PREFIX="${EGRESS_TEST_PREFIX:-10.213}"
SUBNET=$PREFIX.90.0/24; OTHER_SUBNET=$PREFIX.91.0/24
DB_IP=$PREFIX.90.10; PEER_IP=$PREFIX.90.20; GATEWAY=$PREFIX.90.1
GUARD=fh-egress-guard-test
MAX_CONN=20
CONF="$(mktemp)"
SOCAT=alpine/socat:1.8.0.3
GUARD_IMAGE="${EGRESS_GUARD_IMAGE:-funchole-egress-guard:dev}"
# The same list goes to the guard (what may be queried) and to the sandboxes (what they do query), exactly as in compose.
DNS_FOR_SANDBOX="${EGRESS_DNS_SERVERS:-8.8.8.8,8.8.4.4}"
# Canary ports on the Docker host's network. On a busy host (a production VPS) pick free ones.
HOST_CANARY_PORT="${EGRESS_TEST_HOST_PORT:-9100}"; META_PORT="${EGRESS_TEST_META_PORT:-80}"
META_IP="${EGRESS_TEST_META_IP:-169.254.169.254}"

cleanup() {
  set +e
  docker rm -f "$GUARD" fh-eg-db fh-eg-peer fh-eg-host fh-eg-meta fh-eg-platform >/dev/null 2>&1
  docker run --rm --network host --cap-add NET_ADMIN -e EGRESS_CONF=/dev/null $GUARD_IMAGE --remove >/dev/null 2>&1
  docker run --rm --network host --cap-add NET_ADMIN alpine sh -c 'ip addr del $META_IP/32 dev lo' >/dev/null 2>&1
  docker network rm "$NET" "$OTHER" >/dev/null 2>&1
  rm -f "$CONF"
}
[[ "${EGRESS_TEST_KEEP:-}" == "1" ]] || trap cleanup EXIT
cleanup

# Images are built if missing; a host that must not build (a small production VPS) preloads them.
SANDBOX_IMAGE_NAME="${SANDBOX_IMAGE:-funchole-sandbox:dev}"
docker image inspect "$SANDBOX_IMAGE_NAME" >/dev/null 2>&1 || docker build -q -f docker/sandbox/Dockerfile -t "$SANDBOX_IMAGE_NAME" . >/dev/null
if [[ -n "${EGRESS_GUARD_IMAGE:-}" ]]; then docker image inspect "$GUARD_IMAGE" >/dev/null; else docker build -q -f docker/egress/Dockerfile -t "$GUARD_IMAGE" . >/dev/null; fi
RESOLV_MOUNTS=(-v /etc/resolv.conf:/host/resolv.conf:ro)
[[ -d /run/systemd/resolve ]] && RESOLV_MOUNTS+=(-v /run/systemd/resolve:/host/systemd-resolve:ro)

echo "== networks"
docker run --rm --network host --cap-add NET_ADMIN $GUARD_IMAGE --preflight || { echo "host is not ready for the guard (see above)" >&2; exit 1; }
for existing in $(docker network ls -q); do
  if docker network inspect "$existing" --format '{{range .IPAM.Config}}{{.Subnet}} {{end}}' | grep -q "^$PREFIX\."; then
    echo "a Docker network already uses $PREFIX.x.x; set EGRESS_TEST_PREFIX to a free /16 prefix (e.g. 10.214)" >&2; exit 1
  fi
done
docker network create --subnet "$SUBNET" -o com.docker.network.bridge.name=$BR -o com.docker.network.bridge.enable_icc=false "$NET" >/dev/null
docker network create --subnet "$OTHER_SUBNET" "$OTHER" >/dev/null

echo "== canaries"
listen() { echo "socat TCP-LISTEN:$1,fork,reuseaddr SYSTEM:true"; }
docker run -d --name fh-eg-db --network "$NET" --ip "$DB_IP" --entrypoint sh $SOCAT -c "$(listen 5432) & $(listen 5433) & wait" >/dev/null
docker run -d --name fh-eg-peer --network "$NET" --ip "$PEER_IP" --entrypoint sh $SOCAT -c "$(listen 8000)" >/dev/null
docker run -d --name fh-eg-platform --network "$OTHER" --entrypoint sh $SOCAT -c "$(listen 5432)" >/dev/null
docker run -d --name fh-eg-host --network host --entrypoint sh $SOCAT -c "$(listen $HOST_CANARY_PORT)" >/dev/null
docker run --rm --network host --cap-add NET_ADMIN alpine sh -c 'ip addr add $META_IP/32 dev lo' >/dev/null
docker run -d --name fh-eg-meta --network host --entrypoint sh $SOCAT -c "socat TCP-LISTEN:$META_PORT,bind=$META_IP,fork,reuseaddr SYSTEM:true" >/dev/null

echo "== guard"
docker run -d --name "$GUARD" --network host --cap-add NET_ADMIN \
  -e "EGRESS_NETWORKS=sandbox|$BR|$SUBNET|$DB_IP:5432" "${RESOLV_MOUNTS[@]}" \
  -e EGRESS_DNS_SERVERS=$DNS_FOR_SANDBOX \
  -e EGRESS_MAX_CONN=$MAX_CONN -e EGRESS_REAPPLY_SECONDS=3 $GUARD_IMAGE >/dev/null
for _ in $(seq 1 30); do docker exec "$GUARD" egress-guard.sh --check && break; sleep 1; done
docker exec "$GUARD" egress-guard.sh --check || { echo "guard did not come up" >&2; docker logs "$GUARD" >&2; exit 1; }

rules_before="$(docker exec "$GUARD" iptables -S FH-EGRESS | wc -l | tr -d ' ')"
sleep 7   # at least two re-applies: the rule set must not grow
rules_after="$(docker exec "$GUARD" iptables -S FH-EGRESS | wc -l | tr -d ' ')"
[[ "$rules_before" == "$rules_after" ]] || { echo "FAIL: rules are not idempotent ($rules_before -> $rules_after)" >&2; exit 1; }
echo "rules: $rules_after (stable across re-applies)"

attack() {
  ( cd runtime && \
    SANDBOX_NETWORK="$NET" SANDBOX_DNS="$DNS_FOR_SANDBOX" SANDBOX_ADD_HOSTS="tenant-db:$DB_IP" \
    ATTACK_INTERNAL_HOSTS="$GATEWAY:$HOST_CANARY_PORT,$PEER_IP:8000,$DB_IP:5433,$META_IP:$META_PORT,10.255.255.1:80,192.168.1.1:80" \
    ATTACK_SMTP_TARGETS="1.1.1.1:25,1.1.1.1:587" \
    ATTACK_INTERNAL_NAMES="fh-eg-platform" \
    ATTACK_ALLOWED_TARGETS="$DB_IP:5432,tenant-db:5432,1.1.1.1:443" \
    ATTACK_ALLOWED_NAMES="example.com" ATTACK_DIRECT_DNS="9.9.9.9" \
    ATTACK_FLOOD_TARGET="1.1.1.1:443" ATTACK_FLOOD_LIMIT=$MAX_CONN \
    node attack-suite/run.mjs --target sandbox )
}

echo "== attack suite from inside a sandbox on the guarded network"
attack | tee /tmp/egress-attack.out
status=${PIPESTATUS[0]}

echo "== rule counters (proof the rules matched real traffic)"
for chain in FH-DROP-PRIVATE FH-DROP-SMTP FH-DROP-DNS FH-DROP-RATE; do
  packets="$(docker exec "$GUARD" iptables -L "$chain" -nvx | awk 'NR>2 && $3=="DROP"{p+=$1} END{print p+0}')"
  echo "$chain dropped packets: $packets"
  [[ "$packets" -gt 0 ]] || { echo "FAIL: $chain never matched" >&2; status=1; }
done

echo "== logs"
sleep 2
log_summary="$(docker logs "$GUARD" 2>&1 | grep -E "^egress (DENIED|allowed)" | awk '{print $2}' | sort | uniq -c)"
echo "$log_summary"
for label in DENIED-private DENIED-smtp DENIED-dns DENIED-rate allowed; do
  grep -q "$label" <<<"$log_summary" || { echo "FAIL: no '$label' log lines" >&2; status=1; }
done

echo "== fail closed: stop the guard, rules must stay"
docker stop "$GUARD" >/dev/null
( cd runtime && SANDBOX_NETWORK="$NET" ATTACK_INTERNAL_HOSTS="$GATEWAY:$HOST_CANARY_PORT,$PEER_IP:8000" ATTACK_SMTP_TARGETS="1.1.1.1:25" node attack-suite/run.mjs --target sandbox ) | grep -E "T1\.4|T1\.10" -A1
( cd runtime && SANDBOX_NETWORK="$NET" ATTACK_INTERNAL_HOSTS="$GATEWAY:$HOST_CANARY_PORT,$PEER_IP:8000" ATTACK_SMTP_TARGETS="1.1.1.1:25" node attack-suite/run.mjs --target sandbox >/dev/null ) || { echo "FAIL: network opened up after the guard stopped" >&2; status=1; }

[[ $status -eq 0 ]] && echo "EGRESS TEST PASSED" || echo "EGRESS TEST FAILED"
exit $status
