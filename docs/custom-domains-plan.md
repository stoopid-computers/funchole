# Custom Domains for Gateways

## Context

Today every Gateway's public hostname is a randomly-assigned subdomain of one of the
admin's verified base domains (e.g. `5zyu0p.funchole.dev`), picked automatically at
creation so users can start using it immediately - this part already works and stays
unchanged. The new ask: let an end user point their **own** domain at one of their
Gateways instead - a subdomain like `hello.example.com` via CNAME, or an apex domain like
`example.com` via A record - so requests to it route exactly like requests to the
gateway's own auto-assigned hostname. This is the Vercel/Netlify "add a custom domain to
your project" pattern.

Confirmed by direct investigation that this is cleanly additive, not a rework:

- The Gateway's real hostname routing (`GatewayRegistry.findByHostname`/`routingFor`,
  SNI/TLS context selection) is already a flat, shape-agnostic `hostname -> gatewayId`
  map - nothing there assumes the `<key>.<domain>` shape or needs to change.
- The ACME HTTP-01 challenge server (`AcmeChallengeHandler`) only inspects the challenge
  URL path, never the `Host` header - it already serves any hostname that resolves to
  this server, with zero changes needed.
- The one place the `<key>.<domain>` shape is actually baked in is the SQL in
  `GatewayRegistryLoader.loadEntries()` that computes each entry's hostname - extending
  that to also emit entries for verified custom domains is the crux of the whole feature.
- The existing admin-only `AppDomain`/`DomainService` TXT-verification flow
  (`funchole-<id>.<hostname>` TXT record, live `dnsjava` lookup, 3s timeout) is directly
  reusable as an algorithm, just not as the same table/entity (see below).

Decided with the user: a **new**, independent `MAX_CUSTOM_DOMAINS` package quota (not
reusing the existing admin-only `MAX_DOMAINS`, which is 0 on the free package today);
**TXT-only** verification, no separate CNAME/A resolution check; support **both**
subdomain (CNAME) and apex (A record) custom domains - these need no different backend
handling, only different DNS-setup instructions to show the user, so the feature exposes
the Gateway server's public IP as well as the CNAME target.

## Design

### 1. New table: `custom_domains`

A dedicated table, not a reuse/extension of the existing `certificates` table:
`certificates.gateway_id` is `UNIQUE` (today's one-cert-per-gateway invariant that
`GatewayCertificateService` relies on throughout), and `certificates` has no ownership or
DNS-verification columns - it assumes its hostname is already trusted. `custom_domains`
merges the `app_domains` (ownership + TXT verification) and `certificates` (cert
lifecycle) concerns into one table, since a custom domain's cert is 1:1 with it (no
gateway-style reuse case exists here).

New migration `controlplane/src/main/resources/db/migration/V28__create_custom_domains_table.sql`:

```sql
CREATE TABLE custom_domains (
    id UUID PRIMARY KEY,
    gateway_id UUID NOT NULL REFERENCES gateways(id) ON DELETE CASCADE ON UPDATE CASCADE,
    app_user_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE ON UPDATE CASCADE,
    hostname VARCHAR(255) NOT NULL,
    verification_code VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    cert_provider VARCHAR(100) NOT NULL DEFAULT 'SELF_SIGNED',
    cert_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    cert_secret_ref VARCHAR(255),
    cert_issued_at TIMESTAMP WITH TIME ZONE,
    cert_expires_at TIMESTAMP WITH TIME ZONE,
    cert_renewed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_custom_domains_hostname UNIQUE (hostname)
);

CREATE INDEX idx_custom_domains_gateway_id ON custom_domains (gateway_id);
CREATE INDEX idx_custom_domains_app_user_id ON custom_domains (app_user_id);
CREATE INDEX idx_custom_domains_status ON custom_domains (status);
CREATE INDEX idx_custom_domains_cert_status ON custom_domains (cert_status);
CREATE INDEX idx_custom_domains_cert_expires_at ON custom_domains (cert_expires_at);
```

`hostname` is globally UNIQUE (prevents two users squatting the same domain).
`app_user_id` is denormalized from `gateway.appUser` deliberately, matching how
`AppDomain`/`Gateway` both already carry their own `app_user_id` directly rather than
requiring a join for ownership checks (see `GatewayRepository.findByIdAndAppUser_Id`).
`ON DELETE CASCADE` on `gateway_id` matches `certificates.gateway_id`'s existing behavior.

Second migration `V29__add_max_custom_domains_package_limit.sql`, mirroring `V27`'s
seeding pattern, default **free = 1** (matches the free package's `MAX_GATEWAYS=1` - one
custom domain per the one gateway slot free users get):

```sql
INSERT INTO package_limits (id, package_id, limit_key, limit_value)
VALUES ('33333333-3333-3333-3333-333333333339', '33333333-3333-3333-3333-333333333333', 'MAX_CUSTOM_DOMAINS', 1)
ON CONFLICT (package_id, limit_key) DO NOTHING;
```

### 2. Entity + Repository

New `constant/CustomDomainStatus.java` (`PENDING`, `VERIFIED`).

New `entity/CustomDomain.java`, modeled on `AppDomain.java` + the cert fields from
`GatewayCertificate.java` combined into one entity: `@ManyToOne Gateway gateway`,
`@ManyToOne AppUser appUser`, `hostname`, `verificationCode`, `status`, plus
`certProvider`/`certStatus`/`certSecretRef`/`certIssuedAt`/`certExpiresAt`/`certRenewedAt`.
Factory `CustomDomain.create(gateway, appUser, hostname, verificationCode)` and mutators
`markVerified()`, `markCertActive(issuedAt, expiresAt)`, `markCertFailed()`,
`updateCertProvisioningTarget(provider, secretRef)`.

New `repository/CustomDomainRepository.java`:
```java
List<CustomDomain> findAllByGateway_Id(UUID gatewayId);
Page<CustomDomain> findAllByAppUser_Id(UUID appUserId, Pageable pageable);
Optional<CustomDomain> findByIdAndAppUser_Id(UUID id, UUID appUserId);
long countByAppUser_Id(UUID appUserId);
List<CustomDomain> findByCertStatusIn(Collection<CertificateStatus> statuses);
List<CustomDomain> findByCertStatus(CertificateStatus status);
List<CustomDomain> findByCertStatusAndCertExpiresAtBefore(CertificateStatus status, OffsetDateTime before);
```
(mirrors `GatewayCertificateRepository`'s three finder methods for the scheduled jobs.)

### 3. `service/CustomDomainService.java`

Mirrors `DomainService`'s TXT-verification methods (`hasMatchingTxtRecord`,
`toAbsoluteName`, `buildResolver`, `normalizeTxtValue`, `generateVerificationCode`)
**duplicated, not extracted into a shared utility** - this codebase consistently favors
small focused services over early abstraction (`DomainService`, `GatewayCertificateService`,
`GatewayService` are all single-purpose with private helpers, no shared "Utils" classes),
and the two verification flows differ in what they gate on (admin-only vs. ownership),
so a shared extraction buys little for ~50 duplicated lines.

```java
@Transactional
public CustomDomain attachCustomDomain(AppUser appUser, UUID gatewayId, String hostname) {
    Gateway gateway = gatewayRepository.findByIdAndAppUser_Id(gatewayId, appUser.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Gateway not found: " + gatewayId));
    packageLimitService.enforce(appUser.getId(), PackageLimitKey.MAX_CUSTOM_DOMAINS,
            customDomainRepository.countByAppUser_Id(appUser.getId()));
    CustomDomain customDomain = CustomDomain.create(gateway, appUser, hostname.toLowerCase(), generateVerificationCode());
    return customDomainRepository.save(customDomain);
}
```

No admin/cloud-mode gate (unlike `DomainService.createDomain`) - this is explicitly an
end-user-facing feature. Ownership is checked once at attach-time via the same guarded
`findByIdAndAppUser_Id` pattern `GatewayService` already uses everywhere.

`initiateCustomDomainVerification(appUserId, customDomainId)`: same shape as
`DomainService.initiateDomainVerification` (idempotent once `VERIFIED`, TXT record name
`funchole-<customDomainId>.<hostname>`) - on a successful match, calls
`markVerified()` + save, then immediately calls
`customDomainCertificateService.ensureCertificate(savedCustomDomain)` (injected) to kick
off cert issuance, mirroring how `GatewayService.provisionGateway` calls
`ensureCertificate` right after saving.

Also: `listCustomDomains(appUserId, page, size)`, `listCustomDomainsForGateway(appUserId, gatewayId)`,
`getCustomDomainById(appUserId, id)`, `detachCustomDomain(appUserId, id)` (plain delete).

Add `MAX_CUSTOM_DOMAINS` to `constant/PackageLimitKey.java`'s enum, and add
`case MAX_CUSTOM_DOMAINS -> "custom domain(s)";` to `PackageLimitService.describe()`'s
switch (`PackageLimitService.java:94-102`) - **that switch has no `default` branch, so
the build breaks until this case is added.**

### 4. `service/CustomDomainCertificateService.java`

Structurally identical to `GatewayCertificateService` (`ensureCertificate`/
`onProvisionRequested`/three `@Scheduled` jobs/`provisionCertificate`), operating on
`CustomDomain` directly (its cert fields live on the row itself, no separate join table).
New event `event/CustomDomainCertificateProvisionRequested(UUID customDomainId)`.
`provisionCertificate` calls the same `certificateGenerator.generate(new
CertificateRequest(hostname, List.of(hostname)))` single-SAN shape - **identical call**,
this component has zero awareness of AppDomain/Gateway/CustomDomain already. `secretRef`
= `"certificates/custom-domains/" + customDomain.getId()`.

A separate service (not teaching `GatewayCertificateService` to also iterate
`custom_domains`) because the two entities have different field names/repositories with
no code-sharing benefit beyond the three methods' identical *shape* - same
duplicate-over-abstract reasoning as §3.

### 5. `GatewayRegistryLoader` change (the piece that makes routing actually work)

Modify `gateway/src/main/java/com/funchole/backend/gateway/GatewayRegistryLoader.java`'s
`loadEntries()` (currently lines 155-200): keep the existing gateway-hostname query
unchanged, then call a new `loadCustomDomainEntries(entries)` that runs a **second query**
(not a UNION - different join shape, keeps each query independently readable, matching
how `loadEntries()`/`loadRouting()` are already separate methods) and appends into the
same map:

```java
select cd.hostname, cd.cert_secret_ref, cd.cert_provider,
       g.id as gateway_id, g.name as gateway_name, g.unique_key
from custom_domains cd
join gateways g on g.id = cd.gateway_id
where cd.status = 'VERIFIED'
  and cd.cert_status = 'ACTIVE'
  and cd.cert_secret_ref is not null
  and g.status = 'ACTIVE'
```

For each row, build a `GatewayCertificateRecord` with the custom `hostname` and the
**same `gatewayId`** as the owning gateway, run it through the existing `toRuntimeEntry`,
and `entries.put(entry.hostname(), entry)`. No change needed to `GatewayRegistry.
routingFor(UUID gatewayId)` or `findByHostname` - routing is already keyed by `gatewayId`
carried inside `GatewayRuntimeEntry`, hostname is only the outer map's lookup key. SNI/TLS
context selection (`GatewayChannelInitializer`) is equally untouched - any hostname with
its own loaded entry gets its own `SslContext` automatically.

### 6. `config/GatewayNetworkProperties.java` (for apex-domain A-record instructions)

No existing env var exposes the Gateway server's own public IP (confirmed: no hits for
`PUBLIC_IP`/`GATEWAY_IP`/`SERVER_IP` anywhere). New record, same pattern as
`CertificateProperties`:

```java
@ConfigurationProperties(prefix = "app.gateway-network")
public record GatewayNetworkProperties(String publicIp) {}
```

No `@EnableConfigurationProperties` needed - `FuncHoleBackendApplication` already has
`@ConfigurationPropertiesScan`, which auto-registers any `@ConfigurationProperties` class
in the scanned package. Add to `application.yml` near the `certificate:` block:
`gateway-network.public-ip: ${GATEWAY_PUBLIC_IP:}` and a commented block in `.env.example`
explaining it's optional/only needed for apex custom domains. Surfaced as a field on the
response DTO (an API value, not docs-only - this is an MCP-native product, an agent is
the primary consumer of these instructions, not a human reading a README).

### 7. REST: `controller/CustomDomainController.java`

New controller (not extending `GatewayController`/`DomainController`) - this codebase's
convention is one controller per top-level resource, and a custom domain has its own
addressable lifecycle:

```
POST   /api/v1/gateways/{gatewayId}/custom-domains   create
GET    /api/v1/gateways/{gatewayId}/custom-domains   list for one gateway
GET    /api/v1/custom-domains                        list all of the user's, paginated
GET    /api/v1/custom-domains/{id}                    get one
POST   /api/v1/custom-domains/{id}/verification       re-check TXT record
DELETE /api/v1/custom-domains/{id}                    detach
```

New DTOs `dto/CustomDomainCreateRequest.java` (`hostname`, validated) and
`dto/CustomDomainResponse.java` (`id, gatewayId, gatewayHostname, hostname, status,
verificationCode, certStatus, gatewayPublicIp, createdAt, updatedAt`).
`gatewayHostname` (the CNAME target) needs `GatewayCertificateService.buildHostname(gateway)`
(already `public`) - a computed cross-entity value set in the service/mapper layer, not
plain MapStruct field mapping. New `mapper/CustomDomainMapper.java` for the rest.

### 8. MCP: `mcp/CustomDomainMcpTools.java`

Mirrors `DomainMcpTools.java` exactly (`@Service`, constructor-injects `CustomDomainService`
+ `ProfileService` + `CustomDomainMapper`, `CurrentMcpUser.id()` for the caller):

- `attach_custom_domain(gatewayId, hostname)` - returns PENDING with the TXT code, CNAME
  target (`gatewayHostname`), and A-record target (`gatewayPublicIp`, may be blank).
- `verify_custom_domain(customDomainId)` - re-checks TXT, mirrors `initiate_domain_verification`.
- `list_custom_domains(gatewayId?, page?, size?)`.
- `detach_custom_domain(customDomainId)`.

Named `attach`/`verify`/`detach` (not `create`/`delete`) to read distinctly from the
existing `create_domain`/`initiate_domain_verification` tools in an agent's tool list,
since "domain" and "custom domain" are easy to conflate otherwise.

## Files

- New: `controlplane/src/main/resources/db/migration/V28__create_custom_domains_table.sql`
- New: `controlplane/src/main/resources/db/migration/V29__add_max_custom_domains_package_limit.sql`
- New: `controlplane/.../constant/CustomDomainStatus.java`
- New: `controlplane/.../entity/CustomDomain.java`
- New: `controlplane/.../repository/CustomDomainRepository.java`
- New: `controlplane/.../service/CustomDomainService.java`
- New: `controlplane/.../service/CustomDomainCertificateService.java`
- New: `controlplane/.../event/CustomDomainCertificateProvisionRequested.java`
- New: `controlplane/.../config/GatewayNetworkProperties.java`
- New: `controlplane/.../dto/CustomDomainCreateRequest.java`, `CustomDomainResponse.java`
- New: `controlplane/.../mapper/CustomDomainMapper.java`
- New: `controlplane/.../controller/CustomDomainController.java`
- New: `controlplane/.../mcp/CustomDomainMcpTools.java`
- Modified: `controlplane/.../constant/PackageLimitKey.java` (+`MAX_CUSTOM_DOMAINS`)
- Modified: `controlplane/.../service/PackageLimitService.java` (+switch case, line ~99)
- Modified: `gateway/src/main/java/com/funchole/backend/gateway/GatewayRegistryLoader.java`
  (`loadEntries()`, +`loadCustomDomainEntries` method, lines 155-200)
- Modified: `controlplane/src/main/resources/application.yml` (+`gateway-network` block)
- Modified: `.env.example` (+`GATEWAY_PUBLIC_IP` doc block)
- Tests: new `CustomDomainServiceTests` (Testcontainers, mirrors `DomainServiceTests`) for
  ownership enforcement, quota enforcement, idempotent re-verification; a
  `GatewayRegistryLoaderTests` case asserting a verified custom domain produces a second
  map entry with the same `gatewayId` as its owning gateway's own hostname entry.

## Verification

1. `./gradlew :controlplane:compileJava :gateway:compileJava :controlplane:compileTestJava`
   (catches the exhaustive-switch miss immediately if forgotten), then
   `./gradlew :controlplane:test --tests "*Domain*" --tests "*Gateway*" --tests "*PackageLimit*"`.
2. Offline, fully fakeable end-to-end: with `CERTIFICATE_PROVIDER=SELF_SIGNED` (dev
   default), `attach_custom_domain` -> manually mark `VERIFIED` in DB (or genuinely add
   the TXT record on a domain you control, see below) -> confirm `cert_status` reaches
   `ACTIVE` -> `curl -k --resolve customdomain.test:443:127.0.0.1
   https://customdomain.test/<a-real-flow-path>` against the local `docker-compose up
   gateway` stack, confirming SNI + routing dispatch work with zero real DNS involved.
3. Honest TXT+ACME test (can't be faked with `/etc/hosts`): use a domain you actually
   control - even a free one from a dynamic-DNS provider (DuckDNS/dynu, lets you set
   arbitrary TXT+A records) - and drive the real flow: `attach_custom_domain` -> add the
   real TXT record it returns -> `verify_custom_domain` -> assert `VERIFIED`. For ACME,
   point at Let's Encrypt's **staging** server (`CERTIFICATE_ACME_SERVER_URI=
   https://acme-staging-v02.api.letsencrypt.org/directory`) to avoid burning production
   rate limits while confirming the HTTP-01 challenge path genuinely serves a new,
   previously-unknown hostname.
4. Confirm quota: attach `MAX_CUSTOM_DOMAINS` worth of domains on a free-package test
   user, assert the next `attach_custom_domain` call is rejected with the limit message.
