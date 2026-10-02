# Isolated OrbStack deployment

The `funchole-test` machine runs Alpine 3.23 on arm64. Its initial userspace is about 46 MB. OpenJDK 25 is required by this repository; Alpine supplies 25.0.4. The deployment containers reuse the repository's Java 25 and Node 22 runtime stages.

## Machine setup

From macOS:

```sh
orbctl create alpine:3.23 funchole-test --isolated --memory 6G --cpus 4 --disk 32G
orb -m funchole-test -u root apk add --no-cache openjdk25-jdk docker docker-cli-compose fuse-overlayfs nodejs npm python3 curl git bash
```

Isolation disables shared macOS files and SSH-agent access. Copy a source archive with `orbctl push -m funchole-test <archive>` and extract it to `/opt/funchole`. Include current source changes and `scripts/dev/mcp-smoke.py`; exclude `.env`, `.git`, `.vm`, build outputs and dependency caches.

Inside this new machine, configure `/etc/docker/daemon.json` before starting Docker:

```json
{
  "features": {"containerd-snapshotter": false},
  "storage-driver": "fuse-overlayfs"
}
```

```sh
rc-service docker start
rc-update add docker default
```

Docker 29's default containerd snapshotter failed to mount an overlay in this isolated machine. Classic `overlay2` then failed during `dpkg` directory replacement with `Invalid cross-device link`. `fuse-overlayfs` passed the same runtime-image build. Changing drivers hides images from the previous driver; do not change the storage driver on an existing deployment to follow these instructions.

## Build and deploy

Run inside the machine as root, from `/opt/funchole`:

```sh
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk
./gradlew :controlplane:bootJar :gateway:fatJar :dispatcher:fatJar :runtime:fatJar --no-daemon --max-workers=2
sh scripts/orbstack/deploy.sh
python3 scripts/orbstack/verify.py
```

The deployment uses production Compose dependencies, real Postgres, NATS, RustFS and initialized OpenBao, with locally built jars. It generates fresh test credentials in `.vm/private.env`, mode 0600. Do not copy that file back to the repository or print it. `.vm` is ignored by Git.

The verification script refuses to run outside `funchole-test`. It creates test resources through the authenticated MCP API, verifies a real local DNS TXT record, deploys a STATIC site and a Postgres-backed NODE API, and checks both through the deployed Gateway. Two independent HTTP cookie sessions exchange persisted moves, then check state after a Runtime restart. Every repeat creates new resources and a database; it does not clean them up.

TLS uses the stack's deliberately self-signed certificate. The script pins the certificate obtained from the local Gateway, then validates that certificate and hostname for app requests. This tests local TLS and routing, not public DNS, an ACME certificate, or an Internet-facing deployment. The report is saved to `.vm/verification.json` without credentials.

The admin web app is available at `http://funchole-test.orb.local:3000` and MCP at `http://funchole-test.orb.local:7080/api/mcp`. These plaintext management ports are for this disposable local VM only, not a production configuration.

## Integration tests

Most non-E2E tests expect shared Postgres, NATS, S3 storage and OpenBao. Keep them separate from the deployed stack:

```sh
docker run -d --name funchole-unit-db -p 127.0.0.1:15432:5432 -e POSTGRES_DB=funchole -e POSTGRES_USER=funchole -e POSTGRES_PASSWORD=funchole postgres:17.6
docker run -d --name funchole-unit-nats -p 127.0.0.1:14222:4222 nats:2.14.6-alpine -js
docker run -d --name funchole-unit-s3 -p 127.0.0.1:19000:9000 -e RUSTFS_ACCESS_KEY=funchole -e RUSTFS_SECRET_KEY=funchole-secret rustfs/rustfs@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff /data
docker run -d --name funchole-unit-bao -p 127.0.0.1:18200:8200 -e BAO_DEV_ROOT_TOKEN_ID=root openbao/openbao:2.6.1 server -dev -dev-listen-address=0.0.0.0:8200
docker run --rm --network host -e AWS_ACCESS_KEY_ID=funchole -e AWS_SECRET_ACCESS_KEY=funchole-secret -e AWS_DEFAULT_REGION=us-east-1 funchole-vm-rustfs-init \
  aws --endpoint-url http://127.0.0.1:19000 s3api create-bucket --bucket funchole-artifacts
cd runtime/node && npm ci --no-audit --no-fund && cd ../..
mkdir -p .vm/unit-sockets
docker run -d --name funchole-unit-runtime --network host -v /opt/funchole/.vm/unit-sockets:/tmp/funchole \
  -e RUNTIME_INSTANCE_ID=runtime-unit-1 -e RUNTIME_TYPE=NODE -e RUNTIME_WORKER_SOCKET_PATH=/tmp/funchole/runtime-unit-1.sock \
  -e ARTIFACT_STORE_TYPE=s3 -e S3_ARTIFACT_ENDPOINT=http://127.0.0.1:19000 -e S3_ARTIFACT_BUCKET=funchole-artifacts \
  -e S3_ARTIFACT_ACCESS_KEY=funchole -e S3_ARTIFACT_SECRET_KEY=funchole-secret -e S3_ARTIFACT_REGION=us-east-1 \
  -e S3_ARTIFACT_PATH_STYLE_ACCESS=true -e JAVA_TOOL_OPTIONS=-Xmx256m funchole-vm-runtime
docker run -d --name funchole-unit-dispatcher --network host -v /opt/funchole/.vm/unit-sockets:/tmp/funchole \
  -e DB_URL=jdbc:postgresql://127.0.0.1:15432/funchole -e DB_USERNAME=funchole -e DB_PASSWORD=funchole \
  -e NATS_URL=nats://127.0.0.1:14222 -e BAO_ADDR=http://127.0.0.1:18200 -e BAO_TOKEN=root \
  -e DEV_RUNTIME_INSTANCE_ID=runtime-unit-1 -e DEV_RUNTIME_SOCKET_PATH=/tmp/funchole/runtime-unit-1.sock \
  -e JAVA_TOOL_OPTIONS=-Xmx256m funchole-vm-dispatcher
DB_URL=jdbc:postgresql://127.0.0.1:15432/funchole NATS_URL=nats://127.0.0.1:14222 S3_ARTIFACT_ENDPOINT=http://127.0.0.1:19000 BAO_ADDR=http://127.0.0.1:18200 FUNCHOLE_TEST_MCP_SMOKE=true DOCKER_HOST=unix:///var/run/docker.sock \
  ./gradlew :controlplane:test :controlplane:e2eTest --no-daemon --max-workers=2
```

Wait for those services to become ready before creating the bucket or starting tests. The `funchole-vm-*` images come from the deployment above. Two non-E2E Invocation tests need the dedicated Dispatcher and Runtime to consume their NATS messages. E2E tests start their own containers and use a pinned RustFS image, but two fixtures still depend on the configured NATS URL during Spring startup. The Node executor needs its own `pg` dependency installed in `runtime/node`. The former `minio/minio:latest` fixture failed with a registry 404. Test credentials above are fixed, disposable values bound to the guest loopback address. OpenBao dev mode is only for these tests; the deployment itself uses initialized persistent OpenBao.

To pause the machine without deleting data, run `orbctl stop funchole-test` from macOS. Nothing here restarts an existing host stack.

## Verified result

2026-10-02: all deployment services healthy; admin login and Control Plane health reachable from macOS. All four MCP revisions pass against the deployed server. The STATIC and NODE HTTPS checks and two-session persistence check pass. `./gradlew test :controlplane:e2eTest` passes 553 Java tests with zero failures or skips; `npm test` in `runtime/node` passes four tests. See [the redesign plan](mcp-redesign-plan.md#verification-record) for the acceptance record.
