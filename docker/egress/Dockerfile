# Egress guard (docs/SANDBOX_ISOLATION_PRD.md, E3): installs the firewall rules that keep tenant
# sandboxes and build containers away from everything except the public internet and their own
# database. Runs on the HOST network with NET_ADMIN, nothing else.
#
#   docker build -f docker/egress/Dockerfile -t funchole-egress-guard:dev .
FROM alpine:3.22
RUN apk add --no-cache bash iptables tcpdump
COPY docker/egress/egress-guard.sh /usr/local/bin/egress-guard.sh
RUN chmod +x /usr/local/bin/egress-guard.sh
ENTRYPOINT ["/usr/local/bin/egress-guard.sh"]
