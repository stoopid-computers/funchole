package com.funchole.backend.controlplane.mcp;

import com.funchole.backend.controlplane.config.GatewayNetworkProperties;
import com.funchole.backend.controlplane.dto.CustomDomainResponse;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.CustomDomain;
import com.funchole.backend.controlplane.mapper.CustomDomainMapper;
import com.funchole.backend.controlplane.service.CustomDomainService;
import com.funchole.backend.controlplane.service.GatewayCertificateService;
import com.funchole.backend.controlplane.service.ProfileService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

/**
 * MCP tool surface for CustomDomains - mirrors {@code CustomDomainController}.
 * A custom domain must be verified (DNS TXT challenge) before it's routable,
 * exactly like an AppDomain, but any user may attach one to their own
 * Gateway - unlike {@code DomainMcpTools}' admin-only base domains.
 */
@Service
public class CustomDomainMcpTools {

    private final CustomDomainService customDomainService;
    private final ProfileService profileService;
    private final CustomDomainMapper customDomainMapper;
    private final GatewayCertificateService gatewayCertificateService;
    private final GatewayNetworkProperties gatewayNetworkProperties;

    public CustomDomainMcpTools(
            CustomDomainService customDomainService,
            ProfileService profileService,
            CustomDomainMapper customDomainMapper,
            GatewayCertificateService gatewayCertificateService,
            GatewayNetworkProperties gatewayNetworkProperties
    ) {
        this.customDomainService = customDomainService;
        this.profileService = profileService;
        this.customDomainMapper = customDomainMapper;
        this.gatewayCertificateService = gatewayCertificateService;
        this.gatewayNetworkProperties = gatewayNetworkProperties;
    }

    @McpTool(
            name = "attach_custom_domain",
            description = "Attach a custom domain (a subdomain like hello.example.com via CNAME, or an apex domain "
                    + "like example.com via A record) to one of your Gateways, so requests to it route exactly like "
                    + "requests to the gateway's own auto-assigned hostname. Returns PENDING with a DNS TXT "
                    + "verification code, a CNAME target (gatewayHostname, for subdomains), and an A-record target "
                    + "(gatewayPublicIp, for apex domains) - call verify_custom_domain once the DNS record is in place."
    )
    public CustomDomainResponse attachCustomDomain(
            @McpToolParam(description = "Gateway id (UUID) to attach this domain to") String gatewayId,
            @McpToolParam(description = "Hostname to attach, e.g. hello.example.com or example.com") String hostname
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(CurrentMcpUser.id());
        CustomDomain created = customDomainService.attachCustomDomain(appUser, UUID.fromString(gatewayId), hostname);
        return toResponse(created);
    }

    @McpTool(
            name = "verify_custom_domain",
            description = "Check a custom domain's DNS TXT record and mark it VERIFIED if it matches - triggers "
                    + "certificate issuance once verified."
    )
    public CustomDomainResponse verifyCustomDomain(
            @McpToolParam(description = "CustomDomain id (UUID)") String customDomainId
    ) {
        CustomDomain customDomain = customDomainService.initiateCustomDomainVerification(
                CurrentMcpUser.id(), UUID.fromString(customDomainId));
        return toResponse(customDomain);
    }

    @McpTool(name = "list_custom_domains", description = "List the current user's custom domains, optionally scoped to one Gateway.")
    public List<CustomDomainResponse> listCustomDomains(
            @McpToolParam(description = "Gateway id (UUID), optional - omit to list across all your Gateways", required = false) String gatewayId,
            @McpToolParam(description = "1-based page number, defaults to 1 - ignored when gatewayId is set", required = false) Integer page,
            @McpToolParam(description = "Page size, defaults to 20 - ignored when gatewayId is set", required = false) Integer size
    ) {
        if (gatewayId != null) {
            return customDomainService.listCustomDomainsForGateway(CurrentMcpUser.id(), UUID.fromString(gatewayId))
                    .stream()
                    .map(this::toResponse)
                    .toList();
        }

        Page<CustomDomain> result = customDomainService.listCustomDomains(
                CurrentMcpUser.id(), page != null ? page : 1, size != null ? size : 20);
        return result.map(this::toResponse).getContent();
    }

    @McpTool(name = "detach_custom_domain", description = "Detach a custom domain from its Gateway.")
    public Map<String, String> detachCustomDomain(
            @McpToolParam(description = "CustomDomain id (UUID)") String customDomainId
    ) {
        customDomainService.detachCustomDomain(CurrentMcpUser.id(), UUID.fromString(customDomainId));
        return Map.of("message", "Custom domain detached successfully");
    }

    private CustomDomainResponse toResponse(CustomDomain customDomain) {
        String gatewayHostname = gatewayCertificateService.buildHostname(customDomain.getGateway());
        return customDomainMapper.toResponse(customDomain, gatewayHostname, gatewayNetworkProperties.publicIp());
    }

    /** Internal projection for the curated read operation; not an exported tool. */
    public CustomDomainResponse getCustomDomain(String id) {
        return toResponse(customDomainService.getCustomDomainById(CurrentMcpUser.id(), UUID.fromString(id)));
    }
}
