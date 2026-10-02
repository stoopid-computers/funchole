# Development

This document keeps the practical local development notes that do not need to stay on the front page.

For agent connection, on-demand app guides, and protocol smoke tests, see
[MCP development and compatibility](mcp.md). The focused MCP tests run without Docker;
artifact builds, database-backed tests, and real Gateway traffic still require the stack.

## Compose Files

There are two main Compose entrypoints:

* [docker-compose.dev.yml](../docker-compose.dev.yml) for source-mounted development
* [docker-compose.yml](../docker-compose.yml) for built-container startup

Recommended command for day-to-day work:

```bash
docker compose -f docker-compose.dev.yml up --build
```

## Local Services

| Service | Address |
| --- | --- |
| Controlplane | `http://localhost:7080` |
| Gateway | `https://localhost` |
| PostgreSQL | `localhost:5432` |
| OpenBao | `http://localhost:8200` |
| RustFS S3 API | `http://localhost:9000` |
| RustFS Console | `http://localhost:9001` |
| Technitium DNS UI | `http://localhost:5380` |

## Startup Order

Current startup sequence:

1. PostgreSQL becomes healthy
2. OpenBao starts
3. OpenBao bootstrap seeds required secrets
4. `controlplane` starts and runs Flyway migrations
5. RustFS starts for S3-compatible artifact storage
6. `rustfs-init` creates the local artifact bucket and seeds checked-in demo artifacts
7. `runtime` starts and connects to RustFS through generic S3 settings
8. `gateway` starts after `controlplane` is healthy

That dependency exists because the database schema is managed through the `controlplane` startup path.

## Hot Reload Style

In development mode:

* `controlplane` and `gateway` run from mounted source
* a lightweight polling watcher restarts the app process when source changes
* developers do not need to run Gradle directly on the host for ordinary development

Rebuilds are still needed when:

* Docker-related files change
* base image assumptions change
* dependency graph changes require container rebuild

## Useful Commands

Start development stack:

```bash
docker compose -f docker-compose.dev.yml up --build
```

Stop development stack:

```bash
docker compose -f docker-compose.dev.yml down
```

Reset development volumes:

```bash
docker compose -f docker-compose.dev.yml down -v
```

Re-run OpenBao bootstrap:

```bash
docker compose -f docker-compose.dev.yml up openbao-init
```

Compile major modules:

```bash
./gradlew :certificate:compileJava :core:compileJava :controlplane:compileJava :gateway:compileJava
```

Run tests:

```bash
./gradlew test
```

Re-seed checked-in demo Node artifacts into RustFS:

```bash
docker compose -f docker-compose.dev.yml run --rm rustfs-init
```

## Local Workflow

Typical backend workflow:

1. Start the stack
2. Create or use an app user
3. Create a domain
4. Add the required TXT verification record
5. Verify the domain
6. Create a gateway under the verified domain
7. Let `controlplane` provision the certificate
8. Let `gateway` pick up the certificate through registry polling
9. Send HTTPS traffic to the gateway hostname

## DNS Notes

Technitium DNS is included for local DNS experimentation and domain verification.

Important limitation:

* Docker can run the DNS server
* but Docker cannot automatically make every developer machine use that DNS server for a custom domain

So for custom local domains like:

```text
https://gw1.funchole.test/
```

there are still two separate layers:

* project-side DNS inside Docker
* host-machine DNS resolution

This is why wildcard records inside Technitium alone are not enough if the host machine is still using another DNS server.

## HTTPS Notes

`gateway` serves HTTPS on port `443` in development.

Why:

* it keeps the local URL shape close to production
* it avoids exposing a developer-only port in the public hostname pattern

Current development certificates are self-signed, so browser trust warnings are expected unless the local trust chain is configured.

## Gateway Registry Polling

The `gateway` keeps an in-memory registry of active hosts and TLS contexts.

Current behavior:

* it loads an initial snapshot on startup
* it uses a fallback TLS context when there are no gateways yet
* it polls PostgreSQL and OpenBao on a short interval
* new active gateway certificates can be picked up without restarting the container

## Artifact Storage Notes

Runtime uses the `ArtifactStore` contract, so execution is storage-agnostic after an artifact is resolved.

Development mode uses RustFS as the local S3-compatible backend:

```text
ARTIFACT_STORE_TYPE=s3
S3_ARTIFACT_ENDPOINT=http://rustfs:9000
S3_ARTIFACT_BUCKET=funchole-artifacts
S3_ARTIFACT_ACCESS_KEY=funchole
S3_ARTIFACT_SECRET_KEY=funchole-secret
S3_ARTIFACT_REGION=us-east-1
S3_ARTIFACT_PATH_STYLE_ACCESS=true
```

RustFS uses one persisted Docker volume in local development. Production storage topology is future design work and should not copy this local-only setup blindly.

The `rustfs-init` service is development-only. It packages every checked-in demo artifact directory from `runtime/artifacts/dev/<componentVersionId>` and uploads it to RustFS so the seeded `/orders` flow can run without manual S3 setup. This is not the production artifact upload/build pipeline.

Artifact object keys are immutable and based only on the pinned component version:

```text
artifacts/<componentVersionId>/artifact.tar.gz
```

Cache behavior:

* cache hit resolves directly to the local `index.mjs`
* cache miss downloads the exact S3 object key
* the downloaded `artifact.tar.gz` is extracted into `ARTIFACT_CACHE_DIR`
* nested artifact directories are preserved
* `NodeExecutor` receives only the local artifact path

## OpenBao Notes

OpenBao now runs with persistent local storage instead of in-memory dev mode.

That means:

* secret values survive container restart
* certificate bundles remain available after restart
* `controlplane` and `gateway` can read their bootstrap secrets from the persisted OpenBao state

If the local secret state becomes confusing during development, reset the stack volumes and start fresh.

## Google Sign-In Setup

Optional. The admin login page (`http://localhost:3000/login` in dev) can
show a "Sign in with Google" button alongside the existing username/password
form - see `GOOGLE_OAUTH_CLIENT_ID`/`ADMIN_ALLOWED_GOOGLE_EMAILS` in
[docs/environment-variables.md](environment-variables.md). This is not
self-registration: a verified Google sign-in only ever logs in as the one
existing bootstrap admin account, and only when its email is on the
allowlist - it never creates a new account.

To get a Client ID:

1. In [Google Cloud Console](https://console.cloud.google.com/apis/credentials),
   create (or pick) a project, then **Create Credentials → OAuth client ID**.
2. Application type: **Web application**.
3. **Authorized JavaScript origins**: add `http://localhost:3000` for local
   dev (and your real domain, e.g. `https://admin.example.com`, for
   production). No redirect URI is needed - this uses Google Identity
   Services' token flow, not an OAuth redirect.
4. Copy the generated **Client ID** (looks like
   `123456789-abc123.apps.googleusercontent.com`) - there is no client
   secret to configure; ID-token verification only needs the Client ID.
5. Set both in `.env` (see `.env.example`):
   ```
   GOOGLE_OAUTH_CLIENT_ID=123456789-abc123.apps.googleusercontent.com
   ADMIN_ALLOWED_GOOGLE_EMAILS=you@gmail.com
   ```
6. Restart the stack. If the button still doesn't appear, remember
   `NEXT_PUBLIC_GOOGLE_CLIENT_ID` (the frontend's copy of the same value) is
   baked in at *build* time for the production `web` image - rebuild it
   rather than just restarting the container; the dev `web` service doesn't
   have this limitation.

## Cloud Packages & Self-Registration Setup

Optional, and only relevant to the hosted cloud product (`app.funchole.dev`)
- self-hosted installs should leave `CLOUD_MODE_ENABLED` unset and skip this
section entirely. See `CLOUD_MODE_ENABLED` in
[docs/environment-variables.md](environment-variables.md) for what the flag
does. Turning it on requires both Google Sign-In (above, but without an
`ADMIN_ALLOWED_GOOGLE_EMAILS` restriction - any verified Google account may
now self-register) and at least one manually-verified admin domain:

1. Follow the Google Sign-In Setup steps above to get a
   `GOOGLE_OAUTH_CLIENT_ID`. `ADMIN_ALLOWED_GOOGLE_EMAILS` can stay empty -
   it's ignored once `CLOUD_MODE_ENABLED=true`.
2. Once `CLOUD_MODE_ENABLED=true`, `create_domain` only works for the
   bootstrap admin account - every other user gets `ForbiddenException`
   (403). As the admin, create and verify **one or more** real domains you
   control DNS for, through the normal domain-creation flow
   (`create_domain`/`initiate_domain_verification`, REST or MCP):
   1. Create the domain and note the TXT record FuncHole asks you to publish.
   2. Publish that TXT record with your DNS provider.
   3. Call `initiate_domain_verification` (or the equivalent REST endpoint)
      and confirm the domain's status becomes `VERIFIED`.
   4. Repeat for as many domains as you want to spread tenant Gateways
      across (e.g. to stay under per-domain Let's Encrypt rate limits) -
      there's nothing else to configure per domain.
3. Set in `.env` (see `.env.example`):
   ```
   CLOUD_MODE_ENABLED=true
   ```
4. Restart the stack. From this point, any verified Google sign-in that
   isn't already an existing account self-registers: a new `AppUser`,
   assigned the seeded `free` package, with one `Default Gateway`
   auto-provisioned on a **randomly chosen** one of your verified admin
   domains - same for any additional Gateway a non-admin user creates later
   (up to their package's `MAX_GATEWAYS`). If no domain is `VERIFIED` yet,
   sign-up fails with a clear error instead of silently picking nothing.

The `free` package's limits (1 Gateway, 0 attachable Domains, 20 Flows, 100
Functions) live in the `packages`/`package_limits` tables (seeded by
`V26__create_packages_and_quotas.sql`) and can be changed with a plain
`UPDATE` - no deploy needed. A one-off exception for a single user (e.g.
"give this user +1 gateway") goes in `user_package_overrides` instead of
creating a new package tier; a `NULL` `limit_value` there means unlimited
for that user on that one limit key.

Test this against an isolated stack, not the shared dev stack - enabling
cloud mode changes sign-in behavior for every Google account, not just
yours.

## Admin Web / API Reverse Proxy Setup

Optional, cloud product only. In production the Gateway already owns ports
80/443 exclusively (it's single-instance - see `docs/limitations.md`), so
`control-plane-web` (the admin UI, container port 3000) and `controlplane`
(the REST/MCP API, container port 7080) need a way to be reachable over real
HTTPS on those same ports without a second process fighting for them. Rather
than add a separate reverse proxy (Caddy/nginx/Traefik), the Gateway itself
reverse-proxies two fixed hostnames straight to those internal services -
see `ADMIN_WEB_PROXY_HOST`/`CONTROLPLANE_API_PROXY_HOST` in
[docs/environment-variables.md](environment-variables.md).

Each hostname needs its own real `Gateway`/`AppDomain` row so it gets a TLS
certificate through the normal cert-provisioning pipeline (self-signed by
default, or Let's Encrypt once `CERTIFICATE_PROVIDER=LETS_ENCRYPT` is set) -
**with zero Flows attached**, since these hostnames are never dispatched to
a Function/Flow. A `Gateway`'s public hostname is always
`<its unique_key>.<its AppDomain's domain_name>`, so:

1. Create + verify one `AppDomain` for your base domain (e.g. `funchole.dev`)
   through the normal domain-creation flow, exactly like step 2 of the Cloud
   Packages setup above (or reuse that same verified domain if you already
   have one).
2. Create two `Gateway`s on that `AppDomain` (`create_gateway`, REST or MCP,
   signed in as your admin account), leaving each one's Flows empty:
   - `unique_key = "app"` -> serves `app.funchole.dev`
   - `unique_key = "api-controlplane"` -> serves `api-controlplane.funchole.dev`
3. Wait for (or check) both Gateways' certificates reaching `ACTIVE` status -
   same as any tenant Gateway.
4. Set in `.env` (see `.env.example`):
   ```
   ADMIN_WEB_PROXY_HOST=app.funchole.dev
   CONTROLPLANE_API_PROXY_HOST=api-controlplane.funchole.dev
   PUBLIC_CONTROLPLANE_URL=https://api-controlplane.funchole.dev
   CORS_ALLOWED_ORIGINS=https://app.funchole.dev
   ```
   `ADMIN_WEB_PROXY_TARGET`/`CONTROLPLANE_API_PROXY_TARGET` don't need
   setting - they already default to `web:3000`/`controlplane:7080`, the
   right docker-network addresses for `docker-compose.yml`. Don't skip
   `CORS_ALLOWED_ORIGINS` - without it the browser's calls from
   `app.funchole.dev` to `api-controlplane.funchole.dev` fail CORS
   preflight (no `Access-Control-Allow-Origin` header), which looks like a
   network/auth problem in the browser console, not a config one.
5. Rebuild and restart the stack (`PUBLIC_CONTROLPLANE_URL` is baked into
   the `web` image at build time - a plain restart without `--build` won't
   pick it up). `controlplane`'s port 7080 is no longer published to the
   host at all once this is configured; the API is only reachable through
   the Gateway's HTTPS, or from other containers on the docker network.

Use whatever hostnames you actually want in production - `app.funchole.dev`/
`api-controlplane.funchole.dev` above are this repo's own examples, not
fixed values; only the `unique_key`s ("app"/"api-controlplane") need to
match whatever `ADMIN_WEB_PROXY_HOST`/`CONTROLPLANE_API_PROXY_HOST` you set,
since that's what determines each Gateway's served hostname together with
its `AppDomain`.

Test against an isolated stack first, same reasoning as Cloud Packages setup
above - this changes how the one shared Gateway process routes traffic for
every tenant on it.
