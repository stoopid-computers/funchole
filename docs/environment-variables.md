# Environment Variable Reference

Every configuration knob the four runtime services (`controlplane`, `gateway`,
`dispatcher`, `runtime`) read, plus the separate layer of variables Docker
Compose itself substitutes into `docker-compose.yml` before any container
starts. These are two different mechanisms - see [How values actually reach
each service](#how-values-actually-reach-each-service) below if a variable
you set doesn't seem to take effect.

## What you actually need to set for a first production boot

Everything else on this page has a safe default. These do not - `docker
compose -f docker-compose.yml up` refuses to start until they're set, via a
`.env` file at the repo root (see `.env.example`).

| Variable | Required for | Notes |
| --- | --- | --- |
| `S3_ARTIFACT_ACCESS_KEY` | RustFS + controlplane/gateway/runtime | generate a random value, don't reuse the example |
| `S3_ARTIFACT_SECRET_KEY` | RustFS + controlplane/gateway/runtime | same |
| `DB_PASSWORD` | Postgres + every service that talks to it | `openssl rand -hex 32` |
| `JWT_SECRET` | controlplane (via OpenBao) | must be base64 - `openssl rand -base64 48` |
| `BOOTSTRAP_USERNAME` | controlplane (via OpenBao) | leave as `admin` - renaming isn't wired up yet |
| `BOOTSTRAP_PASSWORD` | controlplane (via OpenBao) | `openssl rand -base64 24`; rotates the seeded admin account's password on first boot (see `AdminBootstrapRunner`) |

Optional, safe defaults if left unset: `S3_ARTIFACT_BUCKET` (`funchole-artifacts`),
`S3_ARTIFACT_REGION` (`us-east-1`), `CERTIFICATE_PROVIDER` (`SELF_SIGNED` - set to
`LETS_ENCRYPT` once a real domain's DNS already points at this host).

## controlplane

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_URL` | `jdbc:postgresql://localhost:5432/funchole` | Postgres JDBC URL |
| `DB_USERNAME` | `funchole` | DB user |
| `DB_PASSWORD` | `funchole` | DB password |
| `TENANT_DB_HOST` | `tenant-db` | Host of the separate Postgres server every self-registered user's default `Database` is provisioned on (see `TenantDatabaseProvisioningService`/`CloudSignupService`) - deliberately not the same server as `DB_URL` above, which holds FuncHole's own control-plane schema. |
| `TENANT_DB_PORT` | `5432` | Port of that same server. |
| `TENANT_DB_ADMIN_USERNAME` | `tenant_admin` | Maintenance-only role controlplane connects as to run `CREATE ROLE`/`CREATE DATABASE` - never used for tenant traffic itself. |
| `TENANT_DB_ADMIN_PASSWORD` | `tenant_admin` | Password for that role. **Production must set this** (see `TENANT_DB_ADMIN_PASSWORD` in `.env.example`) - the default only works for local dev. |
| `APP_VERSION` | `0.1.0-SNAPSHOT` | reported app/MCP server version |
| `MCP_TOOL_CALLS_PER_MINUTE` | `120` | Positive per-user MCP tool-call budget per controlplane process. Applies to modern and legacy clients; exhausted budgets return 429 with Retry-After. Set on the controlplane service, not just in the host shell. See [MCP compatibility](mcp.md). |
| `SERVER_PORT` | `7080` | HTTP listen port |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | comma-separated browser origin(s) allowed to call this API cross-origin. **Production must set this** to the real public web origin (e.g. `https://app.funchole.dev`) - the default only works for local dev, and an unset/wrong value fails every browser request with a CORS preflight error, not a clear auth error. Not read by the frontend - set directly on `controlplane`, not `control-plane-web`. |
| `NATS_URL` | `nats://localhost:4222` | NATS broker URL |
| `SOURCE_STORAGE_ROOT` | `/tmp/funchole-sources` | on-disk Function source storage root, only used when `SOURCE_STORE_TYPE=local` |
| `SOURCE_STORE_TYPE` | `local` | `local` (ephemeral unless `SOURCE_STORAGE_ROOT` is a persistent volume) or `s3` (durable - stores source in the same S3-compatible bucket as build artifacts, reusing the `S3_ARTIFACT_*` credentials below; `docker-compose.yml` sets this to `s3` for production) |
| `S3_ARTIFACT_ENDPOINT` | `http://localhost:9000` | S3-compatible artifact store endpoint |
| `S3_ARTIFACT_BUCKET` | `funchole-artifacts` | artifact bucket name |
| `S3_ARTIFACT_ACCESS_KEY` | `funchole` | S3 access key |
| `S3_ARTIFACT_SECRET_KEY` | `funchole-secret` | S3 secret key |
| `S3_ARTIFACT_REGION` | `us-east-1` | S3 region |
| `S3_ARTIFACT_PATH_STYLE_ACCESS` | `true` | use path-style S3 addressing |
| `CERTIFICATE_PROVIDER` | `SELF_SIGNED` | `SELF_SIGNED` or `LETS_ENCRYPT` |
| `CERTIFICATE_SELF_SIGNED_VALIDITY_DAYS` | `30` | self-signed cert validity window |
| `CERTIFICATE_RETRY_DELAY_MS` | `60000` | retry delay on cert issuance failure; also the interval for the renewal/reconciliation scheduled jobs |
| `CERTIFICATE_RENEWAL_WINDOW_DAYS` | `10` | days-before-expiry a certificate gets renewed - deliberately kept below `CERTIFICATE_SELF_SIGNED_VALIDITY_DAYS`'s default so self-signed certs aren't perpetually "due" |
| `CERTIFICATE_ACME_SERVER_URI` | `https://acme-v02.api.letsencrypt.org/directory` | ACME directory URL - point at a staging/test server (e.g. Pebble) to test issuance without touching Let's Encrypt's real rate limits |
| `CERTIFICATE_ACME_POLL_INTERVAL_SECONDS` | `3` | ACME order/challenge status poll interval |
| `CERTIFICATE_ACME_MAX_POLL_ATTEMPTS` | `20` | max ACME poll attempts before giving up |
| `JWT_SECRET` | a known dev value (**must be overridden in production** - see above) | JWT signing secret, must be base64 |
| `JWT_EXPIRATION_SECONDS` | `2592000` (30 days) | Browser login session (JWT) lifetime - unrelated to MCP API keys (`fh_mcp_...`), which don't expire on their own and are revoked individually instead. |
| `BOOTSTRAP_USERNAME` | `admin` | initial admin username (renaming not wired up - see `AdminBootstrapRunner`) |
| `BOOTSTRAP_PASSWORD` | `admin12345` | if changed from this default, rotates the seeded admin account's password on next boot |
| `BAO_ADDR` | `http://localhost:8200` | OpenBao address |
| `BAO_TOKEN` | `root` | OpenBao token - in `docker-compose.yml` this arrives via `BAO_TOKEN_FILE`, not this variable directly (see below) |
| `GOOGLE_OAUTH_CLIENT_ID` | `""` (empty) | Google OAuth 2.0 Client ID (Web application type). Unset = "Sign in with Google" is disabled on the login page and `POST /api/v1/auth/google` rejects every request with a clear "not configured" error - username/password login is unaffected either way. Not secret; the frontend also needs the same value as `NEXT_PUBLIC_GOOGLE_CLIENT_ID` (see `control-plane-web` below) - `docker-compose.yml`/`docker-compose.dev.yml` already wire both from this one variable. |
| `ADMIN_ALLOWED_GOOGLE_EMAILS` | `""` (empty) | Comma-separated allowlist. A verified Google sign-in only succeeds when its email matches one of these exactly (case-insensitive) - this never creates a new account, it only logs in AS the one `BOOTSTRAP_USERNAME` admin account above. Empty means no Google account is authorized, even with `GOOGLE_OAUTH_CLIENT_ID` set. Ignored entirely when `CLOUD_MODE_ENABLED=true` - see below. |
| `CLOUD_MODE_ENABLED` | `false` | **Cloud product only - leave unset for self-hosted installs.** When `true`, any verified Google sign-in (not just `ADMIN_ALLOWED_GOOGLE_EMAILS`) self-registers a new `AppUser` on first login, assigned the seeded `free` package (1 default Gateway, 0 attachable Domains, 20 Flows, 100 Functions - see `V26__create_packages_and_quotas.sql`) and enforced on every Function/Flow/Gateway/Domain creation, REST and MCP alike. Also restricts `create_domain` to the bootstrap admin account only - every other user's Gateway (including their auto-provisioned signup default) lands on a randomly chosen one of the admin's `VERIFIED` domains instead of one they supply themselves; sign-up fails with a clear error if the admin hasn't verified at least one domain yet. `false` (the default) keeps every deployment behaving exactly as before: no self-registration, no quotas, anyone can create a domain, the tables this flag gates go completely unused. See `docs/development.md` for one-time setup. |

## gateway

| Variable | Default | Purpose |
| --- | --- | --- |
| `GATEWAY_PORT` | `443` | HTTPS listen port |
| `ACME_HTTP01_PORT` | `80` | plain-HTTP port for serving Let's Encrypt's HTTP-01 challenge responses; a bind failure here is logged, not fatal - HTTPS traffic on `GATEWAY_PORT` is unaffected |
| `NATS_URL` | `nats://localhost:4222` | NATS broker URL |
| `BAO_ADDR` | `http://localhost:8200` | OpenBao address |
| `BAO_TOKEN` | `""` (empty) | OpenBao token - same `BAO_TOKEN_FILE` indirection as controlplane in `docker-compose.yml` |
| `GATEWAY_INVOCATION_TIMEOUT_MS` | `15000` | how long the gateway waits for a triggered invocation to complete before timing out the HTTP response |
| `GATEWAY_STATIC_SITE_CACHE_DIR` | `/tmp/funchole/gateway-static-site-cache` | local disk cache for STATIC-runtime site files fetched from S3 |
| `GATEWAY_REGISTRY_LOAD_ATTEMPTS` | `30` | retry attempts for the initial gateway-registry load at startup |
| `GATEWAY_REGISTRY_LOAD_DELAY_MS` | `2000` | delay between those retry attempts |
| `GATEWAY_REGISTRY_POLL_INTERVAL_SECONDS` | `5` | interval for the background registry refresh (picks up newly adopted Flows/certificates without a restart) |
| `S3_ARTIFACT_ENDPOINT` | **required, no default** | fetches STATIC-runtime artifacts from the same store Functions are published to |
| `S3_ARTIFACT_BUCKET` | **required, no default** | |
| `S3_ARTIFACT_ACCESS_KEY` | **required, no default** | |
| `S3_ARTIFACT_SECRET_KEY` | **required, no default** | |
| `S3_ARTIFACT_REGION` | `us-east-1` | |
| `S3_ARTIFACT_PATH_STYLE_ACCESS` | `true` | |
| `DB_URL` | `jdbc:postgresql://localhost:5432/funchole` | Postgres JDBC URL |
| `DB_USERNAME` | `funchole` | DB user |
| `DB_PASSWORD` | `funchole` | DB password |
| `ADMIN_WEB_PROXY_HOST` | `""` (empty) | **Cloud product only.** When set (e.g. `app.funchole.dev`), a request whose `Host` header matches this exactly is reverse-proxied straight to `ADMIN_WEB_PROXY_TARGET` instead of the normal AppDomain/Flow dispatch - this is how `control-plane-web` gets served over real HTTPS on the same port 443 without a separate reverse proxy (Caddy/nginx/etc). Requires a real `Gateway`/`AppDomain` row provisioned for this exact hostname so it has a TLS certificate - see `docs/development.md`. Also gives this exact hostname's own `/mcp` path a shortcut straight to controlplane's MCP server (rewritten to its real `/api/mcp` route, forwarded to `CONTROLPLANE_API_PROXY_TARGET`) - so an MCP client can use `<ADMIN_WEB_PROXY_HOST>/mcp` instead of the separate controlplane API domain. Empty (the default) disables this entirely, `/mcp` shortcut included. |
| `ADMIN_WEB_PROXY_TARGET` | `web:3000` | `host:port` the Gateway forwards to when `ADMIN_WEB_PROXY_HOST` matches. Only read when that's set. |
| `CONTROLPLANE_API_PROXY_HOST` | `""` (empty) | Same mechanism as `ADMIN_WEB_PROXY_HOST`, for the controlplane REST/MCP API (e.g. `api-controlplane.funchole.dev`) - what the web app's browser-side JS calls, since it can't reach an internal docker hostname. |
| `CONTROLPLANE_API_PROXY_TARGET` | `controlplane:7080` | `host:port` the Gateway forwards to when `CONTROLPLANE_API_PROXY_HOST` matches. Only read when that's set. |
| `LANDING_PROXY_HOST` | `""` (empty) | Same mechanism as `ADMIN_WEB_PROXY_HOST`, for the marketing landing page (`landing/`, served by the small `landing` service - plain nginx, no build step). Typically the bare AppDomain itself (e.g. `funchole.dev`, no subdomain) - which already has a TLS certificate once that `AppDomain` is `VERIFIED`, no separate `Gateway` row needed for this one specifically, unlike `ADMIN_WEB_PROXY_HOST`/`CONTROLPLANE_API_PROXY_HOST` above. |
| `LANDING_PROXY_TARGET` | `landing:80` | `host:port` the Gateway forwards to when `LANDING_PROXY_HOST` matches. Only read when that's set. |

## dispatcher

| Variable | Default | Purpose |
| --- | --- | --- |
| `NATS_URL` | `nats://localhost:4222` | NATS broker URL |
| `RUNTIME_IPC_ACCEPT_TIMEOUT_MS` | `3000` | timeout accepting a runtime worker's IPC connection |
| `BAO_ADDR` | `http://localhost:8200` | OpenBao address |
| `BAO_TOKEN` | `root` | OpenBao token - same `BAO_TOKEN_FILE` indirection as the other services |
| `DISPATCHER_POLL_TIMEOUT_MS` | `1000` | poll timeout for the main dispatch loop |
| `RUNTIME_REGISTRY_TYPE` | `jdbc` | `memory` selects an in-process-only registry (single dispatcher, loses state on restart); anything else uses the Postgres-backed one (multi-dispatcher safe) |
| `DEV_RUNTIME_INSTANCE_ID` | `runtime-node-dev-1` | **not dev-only despite the name** - this is the dispatcher's only mechanism for registering a runtime worker. Must match the actual worker's `RUNTIME_INSTANCE_ID` |
| `DEV_RUNTIME_TYPE` | `NODE` | must match the worker's `RUNTIME_TYPE` |
| `DEV_RUNTIME_CAPACITY` | `4` | concurrent invocation capacity of that runtime instance |
| `DEV_RUNTIME_SOCKET_PATH` | `/tmp/funchole/runtime-node-dev-1.sock` | must match the worker's `RUNTIME_WORKER_SOCKET_PATH`, and both containers must share a volume mounted at that path |
| `DB_URL` | `jdbc:postgresql://localhost:5432/funchole` | Postgres JDBC URL |
| `DB_USERNAME` | `funchole` | DB user |
| `DB_PASSWORD` | `funchole` | DB password |

## runtime (the Node execution worker)

| Variable | Default | Purpose |
| --- | --- | --- |
| `RUNTIME_WORKER_SOCKET_PATH` | `/tmp/funchole/runtime-node-dev-1.sock` | Unix socket this worker binds for dispatcher IPC |
| `RUNTIME_INSTANCE_ID` | `runtime-node-dev-1` | this worker's instance ID - must match dispatcher's `DEV_RUNTIME_INSTANCE_ID` |
| `RUNTIME_TYPE` | `NODE` | this worker's runtime type |
| `ARTIFACT_DIR` | `artifacts/dev` | artifact root when `ARTIFACT_STORE_TYPE=local` |
| `ARTIFACT_CACHE_DIR` | `/tmp/funchole/artifact-cache` | local cache dir when `ARTIFACT_STORE_TYPE=s3` |
| `ARTIFACT_STORE_TYPE` | `local` | `s3` or `local` - production uses `s3` |
| `NODE_COMMAND` | `node` | command used to launch the persistent Node executor process |
| `NODE_EXECUTOR_SCRIPT_PATH` | `node/executor.mjs` | path to the Node executor script (baked into the `runtime-worker` image as `/app/node/executor.mjs`) |
| `S3_ARTIFACT_ENDPOINT` | **required when `ARTIFACT_STORE_TYPE=s3`** | |
| `S3_ARTIFACT_BUCKET` | **required when `ARTIFACT_STORE_TYPE=s3`** | |
| `S3_ARTIFACT_ACCESS_KEY` | **required when `ARTIFACT_STORE_TYPE=s3`** | |
| `S3_ARTIFACT_SECRET_KEY` | **required when `ARTIFACT_STORE_TYPE=s3`** | |
| `S3_ARTIFACT_REGION` | `us-east-1` | |
| `S3_ARTIFACT_PATH_STYLE_ACCESS` | `true` | |

## control-plane-web

`NEXT_PUBLIC_*` variables are inlined into the browser bundle when the
frontend is built, not read again when the container starts - see "How
values actually reach each service" below for why that makes the
production `web` image's wiring different from every other service here.

| Variable | Default | Purpose |
| --- | --- | --- |
| `NEXT_PUBLIC_CONTROLPLANE_URL` | `http://localhost:7080` | base URL the browser calls for the Controlplane REST API. In the production `web` image this is set from the Compose-level `PUBLIC_CONTROLPLANE_URL` var (e.g. `https://api-controlplane.funchole.dev` once `CONTROLPLANE_API_PROXY_HOST` is configured on the gateway service - see `docs/development.md`), not set directly. |
| `NEXT_PUBLIC_APP_URL` | `""` (empty) | the web app's own public URL, e.g. `https://app.funchole.dev` (set from the Compose-level `PUBLIC_APP_URL` var - should match `ADMIN_WEB_PROXY_HOST` on the gateway service exactly, scheme included). Purely cosmetic: when set, the MCP API Keys page shows `<this>/mcp` as the connect URL (the Gateway's own shortcut - see `ADMIN_WEB_PROXY_HOST` above) instead of `NEXT_PUBLIC_CONTROLPLANE_URL/api/mcp`; unset, it just shows the longer URL, nothing breaks. |
| `NEXT_PUBLIC_GOOGLE_CLIENT_ID` | `""` (empty) | same value as controlplane's `GOOGLE_OAUTH_CLIENT_ID` above - unset means the login page's "Sign in with Google" button simply doesn't render |

## How values actually reach each service

Two separate layers are involved, and it's easy to set a value in the wrong one:

1. **Docker Compose variable substitution** - `${VAR}` references inside
   `docker-compose.yml` itself, resolved from your `.env` file (or the shell
   environment) *before* any container starts. This is what `.env.example`
   documents, and it's the layer with `${VAR:?error message}` (required) and
   `${VAR:-default}` (optional) syntax.
2. **Container-level environment variables** - what the Java process inside
   each container actually reads via `System.getenv`/Spring `${VAR:default}`
   placeholders. Most of these are set directly by `docker-compose.yml`'s
   `environment:` blocks, but three of them go through an extra hop:

   - `controlplane` and `gateway` don't receive `DB_PASSWORD`, `JWT_SECRET`,
     `BOOTSTRAP_USERNAME`, or `BOOTSTRAP_PASSWORD` directly from Compose.
     Instead, `docker/openbao-init/init-openbao.sh` renders them into secret
     documents stored in OpenBao, and each service's entrypoint script
     (`docker/controlplane-entrypoint.sh`, `docker/gateway-entrypoint.sh`)
     calls `export_secret_document` (in `docker/openbao-common.sh`) to fetch
     and `export` those values as real environment variables *before*
     launching the JVM. `dispatcher` is simpler - `DB_PASSWORD` is set
     directly by Compose for it.
   - `BAO_TOKEN_FILE` (e.g. `/openbao/bootstrap/controlplane-token`) is set
     by Compose, but no Java code reads it - only `BAO_TOKEN` is. The
     entrypoint script's `load_bao_token()` reads the file named by
     `BAO_TOKEN_FILE` and exports its contents as `BAO_TOKEN`, which is what
     the JVM actually sees. Each service gets its own least-privilege token
     file (not a shared root token) - see `PRODUCTION_DEPLOYMENT_MISSING.md`
     section 4 for why.
   - `control-plane-web`'s `NEXT_PUBLIC_*` variables are a third case,
     specific to the production `web` image: Next.js inlines them into the
     browser bundle at `npm run build` time, so a plain runtime
     `environment:` entry (layer 2 above) has no effect on them there - the
     production `web` service in `docker-compose.yml` instead passes
     `NEXT_PUBLIC_GOOGLE_CLIENT_ID`, `NEXT_PUBLIC_CONTROLPLANE_URL`
     (sourced from the Compose-level `PUBLIC_CONTROLPLANE_URL`), and
     `NEXT_PUBLIC_APP_URL` (sourced from `PUBLIC_APP_URL`) in as Docker
     build ARGs (see the Dockerfile's `build-web` stage). The dev `web`
     service doesn't need this extra hop - `next dev` reads `NEXT_PUBLIC_*`
     values live from its own container's environment on every compile, so a
     normal `environment:` entry already works there.

If you're overriding a value and it doesn't seem to take effect, check which
layer it actually belongs to first.
