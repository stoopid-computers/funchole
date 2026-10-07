package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.dto.CustomDomainResponse;
import com.funchole.backend.controlplane.dto.DatabaseResponse;
import com.funchole.backend.controlplane.dto.DomainResponse;
import com.funchole.backend.controlplane.dto.EnvironmentProfileConfigResponse;
import com.funchole.backend.controlplane.dto.EnvironmentProfileResponse;
import com.funchole.backend.controlplane.dto.FlowVersionResponse;
import com.funchole.backend.controlplane.dto.GatewayResponse;
import com.funchole.backend.controlplane.config.GatewayNetworkProperties;
import com.funchole.backend.controlplane.mapper.CustomDomainMapper;
import com.funchole.backend.controlplane.service.CustomDomainService;
import com.funchole.backend.controlplane.service.GatewayCertificateService;
import com.funchole.backend.controlplane.service.workflow.ConfigureUseCase;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import jakarta.annotation.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/** Bounded infrastructure operations. Old facades remain ownership-checked implementation details. */
@Service
public class McpInfrastructureTools {
    private final EnvironmentProfileMcpTools environments;
    private final FlowConfigurationMcpTools bindings;
    private final DatabaseMcpTools databases;
    private final GatewayMcpTools gateways;
    private final DomainMcpTools domains;
    private final CustomDomainMcpTools customDomains;
    private final CustomDomainService customDomainService;
    private final CustomDomainMapper customDomainMapper;
    private final GatewayCertificateService certificates;
    private final GatewayNetworkProperties network;
    private final FunctionMcpTools functions;
    private final FlowMcpTools flows;
    private final FlowVersionMcpTools versions;
    private final ConfigureUseCase configureUseCase;

    public McpInfrastructureTools(EnvironmentProfileMcpTools environments, FlowConfigurationMcpTools bindings,
            DatabaseMcpTools databases, GatewayMcpTools gateways, DomainMcpTools domains,
            CustomDomainMcpTools customDomains, CustomDomainService customDomainService,
            CustomDomainMapper customDomainMapper, GatewayCertificateService certificates, GatewayNetworkProperties network,
            FunctionMcpTools functions, FlowMcpTools flows, FlowVersionMcpTools versions,
            ConfigureUseCase configureUseCase) {
        this.environments = environments;
        this.bindings = bindings;
        this.databases = databases;
        this.gateways = gateways;
        this.domains = domains;
        this.customDomains = customDomains;
        this.customDomainService = customDomainService;
        this.customDomainMapper = customDomainMapper;
        this.certificates = certificates;
        this.network = network;
        this.functions = functions;
        this.flows = flows;
        this.versions = versions;
        this.configureUseCase = configureUseCase;
    }

    public record EnvironmentBinding(String reference, @Nullable Integer priority) { }
    public record ConfigRequest(@Nullable String profileRef, @Nullable String key, @Nullable String name, @Nullable String description,
            @Nullable Map<String, String> env, @Nullable Map<String, String> secrets, @Nullable String flowRef,
            @Nullable List<EnvironmentBinding> addEnvironments, @Nullable List<String> removeEnvironments,
            @Nullable List<String> addDatabases, @Nullable List<String> removeDatabases, @Nullable Boolean allowLiveChanges) { }
    public record DatabaseRequest(@Nullable String reference, String name, String host, Integer port,
            String databaseName, String username, @Nullable String password, @Nullable Boolean sslEnabled, @Nullable Boolean allowLiveChanges) { }
    public record GatewayRequest(@Nullable String reference, String name, @Nullable String description, @Nullable String domainRef,
            @Nullable GatewayStatus status, @Nullable Boolean allowLiveChanges) { }
    public enum DomainKind { BASE, CUSTOM }
    public record DomainRequest(@Nullable String reference, @Nullable String hostname, @Nullable String gatewayRef,
            @Nullable DomainKind kind, @Nullable Boolean check) { }
    public enum Disposition { DELETE, ARCHIVE, DETACH }
    public record DnsRequirement(String type, String name, String value) { }
    public record DomainClaim(Object claim, List<DnsRequirement> dnsRequirements) { }
    public record ConfigurationReceipt(List<String> envKeys, List<String> secretKeys) { }

    @McpTool(name = "configure", description = "Patch one shared EnvironmentProfile or explicit Flow bindings. Omitted fields remain unchanged. Existing profile and Flow binding changes require allowLiveChanges=true. Higher environment priority overrides lower priority. Secrets are write-only.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public McpOperationResult configure(@McpToolParam(description = "Exactly one profile mode or Flow-binding mode") ConfigRequest request) {
        return McpOperationResult.run(() -> {
            require(request != null);
            require(request.addEnvironments() == null || request.addEnvironments().stream().noneMatch(java.util.Objects::isNull));
            var add = request.addEnvironments() == null ? null : request.addEnvironments().stream()
                    .map(binding -> new ConfigureUseCase.EnvironmentBinding(
                            McpReference.parse(binding.reference()).require("environments").id(), binding.priority())).toList();
            var result = configureUseCase.execute(CurrentMcpUser.id(), new ConfigureUseCase.Command(
                    request.profileRef() == null ? null : McpReference.parse(request.profileRef()).require("environments").id(),
                    request.key(), request.name(), request.description(), request.env(), request.secrets(),
                    request.flowRef() == null ? null : McpReference.parse(request.flowRef()).require("flows").id(),
                    add, referenceIdsList(request.removeEnvironments(), "environments"),
                    referenceIdsList(request.addDatabases(), "databases"), referenceIdsList(request.removeDatabases(), "databases"),
                    Boolean.TRUE.equals(request.allowLiveChanges())));
            String reference = McpReference.of(result.flow() ? "flows" : "environments", result.resourceId());
            if (result.partialFailure()) return McpOperationResult.failure("PARTIAL_FAILURE",
                    "Changes may have been applied. Read the surviving target before retrying; no automatic rollback was attempted.", reference);
            if (result.flow()) return changed(reference, Map.of("status", "BINDINGS_APPLIED"),
                    "Shared bindings can affect live traffic. Omitted bindings were not removed.");
            return changed(reference, new ConfigurationReceipt(result.envKeys(), result.secretKeys()),
                    "Shared profile changes can affect attached live Flows. Omitted keys remain unchanged. Secret values are excluded.");
        });
    }

    private static List<java.util.UUID> referenceIdsList(List<String> references, String kind) {
        return references == null ? null : references.stream().map(ref -> McpReference.parse(ref).require(kind).id()).toList();
    }

    @McpTool(name = "connect_database", description = "Register a supplied Postgres connection, not provision a database. Updating an owned connection requires allowLiveChanges=true. Password is write-only; omit only on update. SSL defaults to true.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public McpOperationResult connectDatabase(@McpToolParam(description = "Supplied connection and explicit update intent") DatabaseRequest request) {
        return McpOperationResult.run(() -> {
            require(request != null);
            text(request.name(), 150);
            text(request.host(), 255);
            text(request.databaseName(), 255);
            text(request.username(), 255);
            require(request.port() != null && request.port() >= 1 && request.port() <= 65535);
            require(request.password() != null || request.reference() != null);
            if (request.password() != null) require(!request.password().isBlank());
            boolean ssl = request.sslEnabled() == null || request.sslEnabled();
            if (request.reference() != null) {
                live(request.allowLiveChanges());
                String target = id(request.reference(), "databases");
                DatabaseResponse current = databases.getDatabase(target);
                return writes(request.reference(), () -> changed(request.reference(), databases.updateDatabase(target,
                        request.name(), request.host(), request.port(), request.databaseName(), request.username(), request.password(),
                        request.sslEnabled() == null ? current.sslEnabled() : ssl),
                        "Connection changes can affect all attached live consumers. Password is excluded."));
            }
            DatabaseResponse created = databases.createDatabase(request.name(), request.host(), request.port(),
                    request.databaseName(), request.username(), request.password(), ssl);
            return changed(McpReference.of("databases", created.id()), created,
                    "This registers an external connection; it does not provision or verify Postgres. Password is excluded.");
        });
    }

    @McpTool(name = "configure_gateway", description = "Create or update an owned Gateway. New hosted gateways can omit domainRef; admin creation needs a verified base domain. Updates require allowLiveChanges=true and preserve omitted domain/status. Certificate observations are not HTTPS proof.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public McpOperationResult configureGateway(@McpToolParam(description = "Gateway settings and explicit live-change intent") GatewayRequest request) {
        return McpOperationResult.run(() -> {
            require(request != null);
            text(request.name(), 100);
            optionalText(request.description(), 1000);
            String domainId = request.domainRef() == null ? null : id(request.domainRef(), "domains");
            if (domainId != null) require(domains.getDomain(domainId).status() == DomainStatus.VERIFIED);
            if (request.reference() != null) {
                live(request.allowLiveChanges());
                String target = id(request.reference(), "gateways");
                GatewayResponse current = gateways.getGateway(target);
                String resolvedDomain = domainId == null ? current.appDomainId().toString() : domainId;
                return writes(request.reference(), () -> changed(request.reference(), gateways.updateGateway(target,
                        request.name(), request.description() == null ? current.description() : request.description(), resolvedDomain,
                        (request.status() == null ? current.status() : request.status()).name()),
                        "Gateway changes can affect live traffic. Verify external DNS and HTTPS separately."));
            }
            GatewayResponse created = gateways.createGateway(request.name(), request.description(), domainId,
                    (request.status() == null ? GatewayStatus.ACTIVE : request.status()).name());
            return changed(McpReference.of("gateways", created.id()), created,
                    "Certificate state is an observation, not proof that external HTTPS works.");
        });
    }

    @McpTool(name = "claim_domain", description = "Create one base or Gateway custom DNS claim, or inspect an existing claim. Existing claims check DNS only when check=true. New claims return exact external DNS requirements and do not write DNS. Base claims retain service admin restrictions.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = true))
    public McpOperationResult claimDomain(@McpToolParam(description = "One new hostname or an existing claim reference") DomainRequest request) {
        return McpOperationResult.run(() -> {
            require(request != null);
            if (request.reference() != null) {
                require(request.hostname() == null && request.gatewayRef() == null);
                McpReference reference = McpReference.parse(request.reference());
                require(reference.kind().equals("domains") || reference.kind().equals("custom-domains"));
                DomainKind kind = reference.kind().equals("domains") ? DomainKind.BASE : DomainKind.CUSTOM;
                require(request.kind() == null || request.kind() == kind);
                String target = reference.id().toString();
                if (kind == DomainKind.BASE) {
                    DomainResponse current = domains.getDomain(target);
                    if (!Boolean.TRUE.equals(request.check())) return baseClaim(current);
                    return writes(request.reference(), () -> baseClaim(domains.initiateDomainVerification(target)));
                }
                var current = customDomainService.getCustomDomainById(CurrentMcpUser.id(), reference.id());
                if (Boolean.TRUE.equals(request.check())) return writes(request.reference(), () -> customClaim(customDomains.verifyCustomDomain(target)));
                return customClaim(customDomainMapper.toResponse(current, certificates.buildHostname(current.getGateway()), network.publicIp()));
            }
            hostname(request.hostname());
            require(!Boolean.TRUE.equals(request.check()));
            DomainKind kind = request.kind() == null ? (request.gatewayRef() == null ? DomainKind.BASE : DomainKind.CUSTOM) : request.kind();
            if (kind == DomainKind.BASE) {
                require(request.gatewayRef() == null);
                return baseClaim(domains.createDomain(request.hostname()));
            }
            String gatewayId = id(request.gatewayRef(), "gateways");
            gateways.getGateway(gatewayId);
            return customClaim(customDomains.attachCustomDomain(gatewayId, request.hostname()));
        });
    }

    @McpTool(name = "retire", description = "Retire exactly one owned target with explicit DELETE, ARCHIVE or DETACH and allowLiveChanges=true. Flow versions permit DELETE only for DRAFT and ARCHIVE only for ADOPTED. Gateway deletion and custom-domain DETACH are permanent; other identities use existing soft deletion. No inferred cascades.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public McpOperationResult retire(@McpToolParam(description = "Owned target reference") String reference,
            @McpToolParam(description = "Explicit supported retirement behavior") Disposition disposition,
            @McpToolParam(description = "Must be explicitly true") Boolean allowLiveChanges) {
        return McpOperationResult.run(() -> {
            live(allowLiveChanges);
            require(disposition != null);
            McpReference target = McpReference.parse(reference);
            String value = target.id().toString();
            Set<String> deletable = Set.of("functions", "flows", "gateways", "databases", "environments");
            require((deletable.contains(target.kind()) && disposition == Disposition.DELETE)
                    || (target.kind().equals("custom-domains") && disposition == Disposition.DETACH)
                    || (target.kind().equals("flow-versions") && (disposition == Disposition.DELETE || disposition == Disposition.ARCHIVE)));
            if (target.kind().equals("flow-versions")) {
                FlowVersionResponse current = versions.getFlowVersion(target.parentId().toString(), value);
                require(current.status() == (disposition == Disposition.DELETE ? FlowVersionStatus.DRAFT : FlowVersionStatus.ADOPTED));
            } else {
                switch (target.kind()) {
                    case "functions" -> functions.getFunction(value);
                    case "flows" -> flows.getFlow(value);
                    case "gateways" -> gateways.getGateway(value);
                    case "databases" -> databases.getDatabase(value);
                    case "environments" -> environments.getEnvironment(value);
                    case "custom-domains" -> customDomainService.getCustomDomainById(CurrentMcpUser.id(), target.id());
                    default -> throw new IllegalArgumentException();
                }
            }
            return writes(reference, () -> {
                switch (target.kind()) {
                    case "functions" -> functions.deleteFunction(value);
                    case "flows" -> flows.deleteFlow(value);
                    case "gateways" -> gateways.deleteGateway(value);
                    case "databases" -> databases.deleteDatabase(value);
                    case "environments" -> environments.deleteEnvironment(value);
                    case "custom-domains" -> customDomains.detachCustomDomain(value);
                    case "flow-versions" -> {
                        if (disposition == Disposition.DELETE) versions.deleteFlowVersion(target.parentId().toString(), value);
                        else versions.archiveFlowVersion(target.parentId().toString(), value);
                    }
                    default -> throw new IllegalArgumentException();
                }
                return changed(reference, Map.of("status", "RETIRED", "disposition", disposition.name()),
                        target.kind().equals("gateways") ? "Gateway deletion is permanent and uses existing database constraints. Live consumers may be affected."
                                : "Live consumers may be affected. No unrelated resource retirement or cascade was requested.");
            });
        });
    }

    private void applyConfig(String target, ConfigRequest request) {
        if (request.env() != null) request.env().forEach((key, value) -> environments.setEnvironmentEnvVar(target, key, value));
        if (request.secrets() != null) request.secrets().forEach((key, value) -> environments.setEnvironmentSecret(target, key, value));
    }

    private static McpOperationResult configured(String reference, ConfigRequest request) {
        return changed(reference, new ConfigurationReceipt(keys(request.env()).stream().sorted().toList(),
                keys(request.secrets()).stream().sorted().toList()), "Shared profile changes can affect attached live Flows. Omitted keys remain unchanged. Secret values are excluded.");
    }

    private static McpOperationResult baseClaim(DomainResponse claim) {
        return changed(McpReference.of("domains", claim.id()), new DomainClaim(claim,
                List.of(new DnsRequirement("TXT", "funchole-" + claim.id() + "." + claim.domainName(), claim.verificationCode()))),
                "Write DNS externally, then check the existing claim. DNS and HTTPS are not configured by this tool.");
    }

    private static McpOperationResult customClaim(CustomDomainResponse claim) {
        var requirements = new java.util.ArrayList<DnsRequirement>();
        requirements.add(new DnsRequirement("TXT", "funchole-" + claim.id() + "." + claim.hostname(), claim.verificationCode()));
        if (claim.gatewayHostname() != null) requirements.add(new DnsRequirement("CNAME", claim.hostname(), claim.gatewayHostname()));
        if (claim.gatewayPublicIp() != null) requirements.add(new DnsRequirement("A", claim.hostname(), claim.gatewayPublicIp()));
        return changed(McpReference.of("custom-domains", claim.id()), new DomainClaim(claim, List.copyOf(requirements)),
                "Use CNAME for a subdomain or A for an apex, plus the TXT challenge. Certificate state is not external HTTPS proof.");
    }

    private static McpOperationResult changed(String reference, Object data, String warning) {
        McpOperationResult result = McpOperationResult.success(reference, data);
        return new McpOperationResult(result.ok(), result.code(), result.message(), result.reference(), result.data(), result.links(), List.of(warning));
    }

    private static McpOperationResult writes(String reference, Callable<McpOperationResult> operation) {
        try { return operation.call(); }
        catch (Exception exception) {
            return McpOperationResult.failure("PARTIAL_FAILURE", "Changes may have been applied. Read the surviving target before retrying; no automatic rollback was attempted.", reference);
        }
    }

    private static String id(String reference, String kind) { return McpReference.parse(reference).require(kind).id().toString(); }
    private static void live(Boolean value) { require(Boolean.TRUE.equals(value)); }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException(); }
    private static void text(String value, int max) { require(value != null && !value.isBlank() && value.length() <= max); }
    private static void optionalText(String value, int max) { require(value == null || value.length() <= max); }
    private static void hostname(String value) {
        text(value, 253);
        require(value.contains(".") && !value.endsWith("."));
        for (String label : value.split("\\.", -1)) require(label.length() <= 63 && label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"));
    }
    private static void validateConfig(Map<String, String> values) {
        if (values == null) return;
        values.forEach((key, value) -> require(key != null && key.length() <= 255
                && key.matches("[A-Za-z_][A-Za-z0-9_]*") && value != null));
    }
    private static Set<String> keys(Map<String, String> values) { return values == null ? Set.of() : values.keySet(); }
    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private static Set<String> referenceIds(List<String> values, String kind) {
        Set<String> ids = new HashSet<>();
        for (String reference : values) require(ids.add(id(reference, kind)));
        return ids;
    }
    private static boolean disjoint(Set<String> a, Set<String> b) { return java.util.Collections.disjoint(a, b); }
    private static Set<String> union(Set<String> a, Set<String> b) { Set<String> result = new HashSet<>(a); result.addAll(b); return result; }
}
