#!/bin/sh
# Run inside the isolated test machine, from the copied FuncHole source root.
set -eu

[ "$(hostname)" = funchole-test ] || { echo "Refusing deployment outside funchole-test" >&2; exit 1; }
export DOCKER_HOST=unix:///var/run/docker.sock
mkdir -p .vm
chmod 700 .vm

python3 - <<'PY'
from pathlib import Path
import secrets
import base64
p = Path('.vm/private.env')
if not p.exists():
    values = {key: secrets.token_hex(24) for key in [
        'S3_ARTIFACT_ACCESS_KEY', 'S3_ARTIFACT_SECRET_KEY', 'DB_PASSWORD',
        'BOOTSTRAP_PASSWORD', 'TENANT_DB_ADMIN_PASSWORD']}
    values['JWT_SECRET'] = base64.b64encode(secrets.token_bytes(48)).decode()
    values['BOOTSTRAP_USERNAME'] = 'admin'
    values['PUBLIC_CONTROLPLANE_URL'] = 'http://funchole-test.orb.local:7080'
    values['CORS_ALLOWED_ORIGINS'] = 'http://funchole-test.orb.local:3000,http://localhost:3000'
    p.touch(mode=0o600)
    p.write_text(''.join(f'{key}={value}\n' for key, value in values.items()))
dns = Path('.vm/dns.conf')
if not dns.exists():
    dns.write_text('no-resolv\nserver=1.1.1.1\nserver=8.8.8.8\n')
PY

cat > .vm/Dockerfile <<'EOF'
FROM funchole-test-runtime-base AS gateway
COPY gateway/build/libs/funchole-gateway.jar /app/app.jar
COPY docker/gateway-entrypoint.sh /opt/funchole/entrypoint.sh
ENTRYPOINT ["sh", "/opt/funchole/entrypoint.sh"]

FROM funchole-test-runtime-base AS dispatcher
COPY dispatcher/build/libs/funchole-dispatcher.jar /app/app.jar
COPY docker/dispatcher-entrypoint.sh /opt/funchole/entrypoint.sh
ENTRYPOINT ["sh", "/opt/funchole/entrypoint.sh"]

FROM funchole-test-runtime-node AS controlplane
COPY controlplane/build/libs/funchole-controlplane.jar /app/app.jar
COPY docker/controlplane-entrypoint.sh /opt/funchole/entrypoint.sh
ENTRYPOINT ["sh", "/opt/funchole/entrypoint.sh"]

FROM funchole-test-runtime-node AS runtime-worker
COPY runtime/build/libs/funchole-runtime.jar /app/app.jar
COPY runtime/node /app/node
RUN cd /app/node && npm ci --no-audit --no-fund --omit=dev && mkdir -p /app/artifacts
ENV NODE_EXECUTOR_SCRIPT_PATH=/app/node/executor.mjs
ENV ARTIFACT_DIR=/app/artifacts/dev
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM alpine:3.23 AS dns
RUN apk add --no-cache dnsmasq
ENTRYPOINT ["dnsmasq", "--keep-in-foreground", "--conf-file=/etc/funchole-dns.conf"]
EOF

cat > .vm/Dockerfile.dockerignore <<'EOF'
.git
.gradle
.env
.vm/private.env
.vm/verification.json
control-plane-web/node_modules
control-plane-web/.next
EOF

cat > .vm/compose.yml <<'EOF'
services:
  rustfs:
    image: rustfs/rustfs@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff
  controlplane:
    build:
      dockerfile: .vm/Dockerfile
    ports:
      - "7080:7080"
    dns:
      - 172.30.0.53
    environment:
      JAVA_TOOL_OPTIONS: "-Xms64m -Xmx512m"
    depends_on:
      dns:
        condition: service_started
  gateway:
    build:
      dockerfile: .vm/Dockerfile
    environment:
      JAVA_TOOL_OPTIONS: "-Xms32m -Xmx256m"
  dispatcher:
    build:
      dockerfile: .vm/Dockerfile
    environment:
      JAVA_TOOL_OPTIONS: "-Xms32m -Xmx256m"
  runtime:
    build:
      dockerfile: .vm/Dockerfile
    environment:
      JAVA_TOOL_OPTIONS: "-Xms32m -Xmx256m"
  web:
    ports:
      - "3000:3000"
  dns:
    build:
      context: .
      dockerfile: .vm/Dockerfile
      target: dns
    volumes:
      - ./.vm/dns.conf:/etc/funchole-dns.conf:ro
    networks:
      funchole-network:
        ipv4_address: 172.30.0.53
networks:
  funchole-network:
    ipam:
      config:
        - subnet: 172.30.0.0/24
EOF

# Reuse the repository's actual Java 25 / Node 22 runtime stages. Package
# locally built jars without recompiling each module in a separate image.
docker build --target runtime-base -t funchole-test-runtime-base .
docker build --target runtime-node-base -t funchole-test-runtime-node .

if [ "${VM_PREPARE_ONLY:-false}" = true ]; then
    exit 0
fi

docker compose -p funchole-vm --env-file .vm/private.env -f docker-compose.yml -f .vm/compose.yml \
    up -d --build --wait --wait-timeout 300 controlplane gateway dispatcher runtime web dns
