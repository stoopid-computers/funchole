#!/usr/bin/env bash
# Egress guard for tenant sandboxes and build containers.
#
# Owns two iptables chains and nothing else:
#   FH-EGRESS  referenced first from DOCKER-USER: traffic crossing the sandbox bridges
#   FH-HOSTIN  referenced first from INPUT: traffic from a sandbox bridge to the host itself
#
# What a sandbox may do, in order:
#   1. receive replies to what it started
#   2. reach its explicitly allowed endpoints (its tenant database); nothing else may start a
#      connection INTO a sandbox network
#   3. ask the real DNS resolvers (and nobody else) for names
#   4. NOT: send mail (SMTP ports), reach private/link-local/metadata ranges, other sandboxes,
#      or the host itself
#   5. open a bounded number of connections and move a bounded amount of data
#   6. everything else (the public internet) is allowed, and new connections are logged
#
# The rules are applied atomically (iptables-restore) and re-applied periodically. They stay in the
# kernel if this container stops: a dead guard never opens the network up. `--remove` takes them out.
#
# Configuration:
#   EGRESS_NETWORKS        networks, ";"-separated, fields "|"-separated:  name|bridge|subnet|ip:port,ip:port
#                          e.g. "sandbox|fhsbx0|10.213.40.0/24|10.213.40.10:5432;build|fhbld0|10.213.41.0/24|"
#   EGRESS_CONF            alternative: a file with one network per line:  <name> <bridge> <subnet> [allow=<ip>:<port>,...]
#   EGRESS_HOST_IPS        comma list of this host's own addresses, blocked as destinations
#   EGRESS_DNS_SERVERS     comma list of resolvers sandboxes may query. Default: the servers Docker itself uses,
#                          read from /host/resolv.conf or /host/systemd-resolve/resolv.conf, else 8.8.8.8, 8.8.4.4
#   EGRESS_BLOCK_PORTS     TCP ports always blocked                      (25,465,587,2525)
#   EGRESS_MAX_CONN        concurrent connections per sandbox            (256)
#   EGRESS_NEW_CONN_RATE   new connections per second per sandbox        (100)
#   EGRESS_BANDWIDTH       data rate per sandbox, iptables hashlimit     (30mb/s)
#   EGRESS_LOG_ALLOWED     log sampled new outbound connections          (true)
#   EGRESS_REAPPLY_SECONDS re-apply interval                             (15)
set -euo pipefail

CONF="${EGRESS_CONF:-/etc/egress/networks.conf}"
BLOCK_PORTS="${EGRESS_BLOCK_PORTS:-25,465,587,2525}"
HOST_IPS="${EGRESS_HOST_IPS:-}"
MAX_CONN="${EGRESS_MAX_CONN:-256}"
NEW_RATE="${EGRESS_NEW_CONN_RATE:-100}"
BANDWIDTH="${EGRESS_BANDWIDTH:-30mb/s}"
LOG_ALLOWED="${EGRESS_LOG_ALLOWED:-true}"
INTERVAL="${EGRESS_REAPPLY_SECONDS:-15}"
STATE="${EGRESS_STATE_DIR:-/tmp}"

# Never reachable from a sandbox, whatever else is allowed. Includes this very subnet space, which is
# what stops one tenant's sandbox from talking to another's.
PRIVATE_RANGES="0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.0.0.0/24 192.168.0.0/16 198.18.0.0/15 224.0.0.0/3"

# NFLOG groups: 100 private/host, 101 allowed, 102 smtp, 103 dns, 104 rate limited
log_rule() { echo "-A $1 -m limit --limit 20/second --limit-burst 40 -j NFLOG --nflog-group $2 --nflog-prefix $3"; }

dns_servers() {
  if [[ -n "${EGRESS_DNS_SERVERS:-}" ]]; then
    echo "${EGRESS_DNS_SERVERS//,/ }"
    return
  fi
  # What Docker's own resolver forwards to: the host's resolv.conf, or, when that only holds a local
  # stub (systemd-resolved's 127.0.0.53), the real upstream in /run/systemd/resolve/resolv.conf.
  local found="" file
  for file in /host/resolv.conf /host/systemd-resolve/resolv.conf; do
    [[ -r "$file" ]] || continue
    found="$(awk '/^nameserver/ && $2 !~ /^127\./ && $2 !~ /:/ {print $2}' "$file" | tr '\n' ' ')"
    [[ -n "$found" ]] && break
  done
  echo "${found:-8.8.8.8 8.8.4.4}"
}

read_networks() {
  # prints "name bridge subnet allow" lines (allow may be empty), from EGRESS_NETWORKS or the config file
  if [[ -n "${EGRESS_NETWORKS:-}" ]]; then
    local entry name bridge subnet allow
    for entry in ${EGRESS_NETWORKS//;/ }; do
      IFS='|' read -r name bridge subnet allow <<<"$entry"
      [[ -n "$name" && -n "$bridge" && -n "$subnet" ]] || { echo "egress-guard: bad EGRESS_NETWORKS entry: $entry" >&2; exit 64; }
      echo "$name $bridge $subnet $allow"
    done
    return
  fi
  grep -vE '^\s*(#|$)' "$CONF" | while read -r name bridge subnet rest; do
    local allow="${rest#allow=}"; [[ "$rest" == allow=* ]] || allow=""
    echo "$name $bridge $subnet $allow"
  done
}

render() {
  local dns; dns="$(dns_servers)"
  cat <<RULES
*filter
:FH-EGRESS - [0:0]
:FH-HOSTIN - [0:0]
:FH-DROP-PRIVATE - [0:0]
:FH-DROP-SMTP - [0:0]
:FH-DROP-DNS - [0:0]
:FH-DROP-RATE - [0:0]
:FH-ALLOW-LOG - [0:0]
$(log_rule FH-DROP-PRIVATE 100 fh-egress-private:)
-A FH-DROP-PRIVATE -j DROP
$(log_rule FH-DROP-SMTP 102 fh-egress-smtp:)
-A FH-DROP-SMTP -j DROP
$(log_rule FH-DROP-DNS 103 fh-egress-dns:)
-A FH-DROP-DNS -j DROP
$(log_rule FH-DROP-RATE 104 fh-egress-rate:)
-A FH-DROP-RATE -j DROP
RULES
  if [[ "$LOG_ALLOWED" == "true" ]]; then
    log_rule FH-ALLOW-LOG 101 fh-egress-allow:
  fi
  echo "-A FH-ALLOW-LOG -j RETURN"

  while read -r name bridge subnet allow; do
    echo "# network $name ($bridge $subnet)"
    # 1. Replies are always fine.
    echo "-A FH-EGRESS -d $subnet -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT"
    echo "-A FH-EGRESS -s $subnet -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT"
    # 2. Explicit allow-list (the tenant database): must come before the "nothing in" rule below,
    #    because the database lives on the same network.
    for endpoint in ${allow//,/ }; do
      echo "-A FH-EGRESS -s $subnet -d ${endpoint%%:*} -p tcp --dport ${endpoint##*:} -j ACCEPT"
    done
    # Nothing may open a connection INTO a sandbox network (other sandboxes, other containers, the internet).
    echo "-A FH-EGRESS -d $subnet -j DROP"
    # 3. DNS only to the real resolvers (may be private addresses, hence before the private-range drop).
    for ns in $dns; do
      echo "-A FH-EGRESS -s $subnet -d $ns -p udp --dport 53 -j ACCEPT"
      echo "-A FH-EGRESS -s $subnet -d $ns -p tcp --dport 53 -j ACCEPT"
    done
    echo "-A FH-EGRESS -s $subnet -p udp --dport 53 -j FH-DROP-DNS"
    echo "-A FH-EGRESS -s $subnet -p tcp --dport 53 -j FH-DROP-DNS"
    # 4. Mail, private ranges, and this host.
    echo "-A FH-EGRESS -s $subnet -p tcp -m multiport --dports $BLOCK_PORTS -j FH-DROP-SMTP"
    for range in $PRIVATE_RANGES; do
      echo "-A FH-EGRESS -s $subnet -d $range -j FH-DROP-PRIVATE"
    done
    for ip in ${HOST_IPS//,/ }; do
      echo "-A FH-EGRESS -s $subnet -d $ip -j FH-DROP-PRIVATE"
    done
    # 5. Per-sandbox limits (each sandbox has its own address, so this is per tenant).
    echo "-A FH-EGRESS -s $subnet -p tcp --syn -m connlimit --connlimit-above $MAX_CONN --connlimit-mask 32 -j FH-DROP-RATE"
    echo "-A FH-EGRESS -s $subnet -m conntrack --ctstate NEW -m hashlimit --hashlimit-name fh-new --hashlimit-mode srcip --hashlimit-above ${NEW_RATE}/second --hashlimit-burst $((NEW_RATE * 2)) -j FH-DROP-RATE"
    echo "-A FH-EGRESS -s $subnet -m hashlimit --hashlimit-name fh-bw --hashlimit-mode srcip --hashlimit-above $BANDWIDTH -j DROP"
    # 6. The public internet: log new connections, then let Docker's own rules finish the job.
    echo "-A FH-EGRESS -s $subnet -m conntrack --ctstate NEW -j FH-ALLOW-LOG"
    echo "-A FH-EGRESS -s $subnet -j RETURN"
    # Traffic from a sandbox to the host itself (not forwarded): only replies.
    echo "-A FH-HOSTIN -i $bridge -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT"
    echo "-A FH-HOSTIN -i $bridge -j FH-DROP-PRIVATE"
  done < <(read_networks)
  echo "COMMIT"
}

ensure_jumps() {
  iptables -C DOCKER-USER -j FH-EGRESS 2>/dev/null || iptables -I DOCKER-USER 1 -j FH-EGRESS
  iptables -C INPUT -j FH-HOSTIN 2>/dev/null || iptables -I INPUT 1 -j FH-HOSTIN
}

# A fingerprint of what is actually loaded, so tampering (or a flush) is noticed and repaired, while an
# unchanged rule set is left alone: re-applying would reset the packet counters.
loaded_fingerprint() {
  iptables -S FH-EGRESS 2>/dev/null | md5sum | cut -d' ' -f1
  iptables -S FH-HOSTIN 2>/dev/null | md5sum | cut -d' ' -f1
}

apply() {
  local wanted_file="$STATE/egress-rules.wanted" fingerprint_file="$STATE/egress-rules.fingerprint"
  render > "$wanted_file.new"
  if [[ -f "$wanted_file" ]] && cmp -s "$wanted_file" "$wanted_file.new" \
      && [[ "$(loaded_fingerprint)" == "$(cat "$fingerprint_file" 2>/dev/null)" ]]; then
    rm -f "$wanted_file.new"
    ensure_jumps
    touch "$STATE/egress-guard-ok"
    return
  fi
  # The chains must exist before the jumps, and the jumps before traffic relies on them; restore is atomic.
  iptables -N FH-EGRESS 2>/dev/null || true
  iptables -N FH-HOSTIN 2>/dev/null || true
  iptables-restore --noflush < "$wanted_file.new"
  mv "$wanted_file.new" "$wanted_file"
  loaded_fingerprint > "$fingerprint_file"
  ensure_jumps
  touch "$STATE/egress-guard-ok"
}

remove() {
  while iptables -D DOCKER-USER -j FH-EGRESS 2>/dev/null; do :; done
  while iptables -D INPUT -j FH-HOSTIN 2>/dev/null; do :; done
  for chain in FH-EGRESS FH-HOSTIN FH-DROP-PRIVATE FH-DROP-SMTP FH-DROP-DNS FH-DROP-RATE FH-ALLOW-LOG; do
    iptables -F "$chain" 2>/dev/null || true
    iptables -X "$chain" 2>/dev/null || true
  done
}

# Host prerequisites. Without br_netfilter, traffic between two containers on the SAME bridge never meets
# iptables, so one tenant's sandbox could reach another's. Report it loudly instead of silently trusting it.
preflight() {
  local ok=0
  if [[ ! -e /proc/sys/net/bridge/bridge-nf-call-iptables ]]; then
    echo "PREFLIGHT FAIL: br_netfilter is not loaded. On the host run:  sudo modprobe br_netfilter && echo br_netfilter | sudo tee /etc/modules-load.d/br_netfilter.conf" >&2; ok=1
  elif [[ "$(cat /proc/sys/net/bridge/bridge-nf-call-iptables)" != "1" ]]; then
    echo "PREFLIGHT FAIL: net.bridge.bridge-nf-call-iptables is 0. On the host run:  sudo sysctl -w net.bridge.bridge-nf-call-iptables=1 (and persist it in /etc/sysctl.d)" >&2; ok=1
  fi
  iptables -S DOCKER-USER >/dev/null 2>&1 || { echo "PREFLIGHT FAIL: no DOCKER-USER chain (is Docker using the iptables firewall backend?)" >&2; ok=1; }
  echo "egress-guard: sandboxes may query DNS servers: $(dns_servers)" >&2
  return $ok
}

healthy() {
  iptables -C DOCKER-USER -j FH-EGRESS 2>/dev/null \
    && iptables -C INPUT -j FH-HOSTIN 2>/dev/null \
    && [[ "$(cat /proc/sys/net/bridge/bridge-nf-call-iptables 2>/dev/null)" == "1" ]] \
    && [[ -n "$(find "$STATE/egress-guard-ok" -mmin -2 2>/dev/null)" ]]
}

start_loggers() {
  # Structured log lines from the kernel's NFLOG groups: "DENIED private src>dst".
  local group label
  for entry in "100 DENIED-private" "102 DENIED-smtp" "103 DENIED-dns" "104 DENIED-rate" "101 allowed"; do
    group="${entry%% *}"; label="${entry##* }"
    ( tcpdump -l -n -q -i "nflog:$group" 2>/dev/null | awk -v label="$label" '{ print "egress " label " " $0; fflush() }' ) &
  done
}

case "${1:-run}" in
  --remove) remove; echo "egress rules removed"; exit 0 ;;
  --check)  healthy; exit $? ;;
  --preflight) preflight; exit $? ;;
  --render) render; exit 0 ;;
  run)
    [[ -n "${EGRESS_NETWORKS:-}" || -r "$CONF" ]] || { echo "egress-guard: set EGRESS_NETWORKS or provide $CONF" >&2; exit 64; }
    preflight || { echo "egress-guard: fix the host prerequisites above, then restart" >&2; exit 78; }
    apply
    echo "egress-guard: rules applied for: $(read_networks | awk '{print $1}' | tr '\n' ' ')"
    start_loggers
    # Stopping the container must NOT remove the rules (fail closed), so no cleanup trap.
    while true; do sleep "$INTERVAL"; apply || echo "egress-guard: re-apply failed" >&2; done
    ;;
  *) echo "usage: egress-guard.sh [run|--remove|--check|--preflight|--render]" >&2; exit 64 ;;
esac
