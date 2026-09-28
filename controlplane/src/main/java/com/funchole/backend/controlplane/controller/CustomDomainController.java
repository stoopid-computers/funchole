package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.config.GatewayNetworkProperties;
import com.funchole.backend.controlplane.dto.CustomDomainCreateRequest;
import com.funchole.backend.controlplane.dto.CustomDomainResponse;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.CustomDomain;
import com.funchole.backend.controlplane.mapper.CustomDomainMapper;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.CustomDomainService;
import com.funchole.backend.controlplane.service.GatewayCertificateService;
import com.funchole.backend.controlplane.service.ProfileService;
import com.funchole.backend.core.base.mapper.PaginationMapper;
import com.funchole.backend.core.base.response.ApiResponse;
import com.funchole.backend.core.base.response.PaginationResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CustomDomainController {
    private final CustomDomainService customDomainService;
    private final ProfileService profileService;
    private final CustomDomainMapper customDomainMapper;
    private final PaginationMapper paginationMapper;
    private final GatewayCertificateService gatewayCertificateService;
    private final GatewayNetworkProperties gatewayNetworkProperties;

    public CustomDomainController(
            CustomDomainService customDomainService,
            ProfileService profileService,
            CustomDomainMapper customDomainMapper,
            PaginationMapper paginationMapper,
            GatewayCertificateService gatewayCertificateService,
            GatewayNetworkProperties gatewayNetworkProperties
    ) {
        this.customDomainService = customDomainService;
        this.profileService = profileService;
        this.customDomainMapper = customDomainMapper;
        this.paginationMapper = paginationMapper;
        this.gatewayCertificateService = gatewayCertificateService;
        this.gatewayNetworkProperties = gatewayNetworkProperties;
    }

    @PostMapping("/gateways/{gatewayId}/custom-domains")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<CustomDomainResponse> attachCustomDomain(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID gatewayId,
            @Valid @RequestBody CustomDomainCreateRequest request
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());
        CustomDomain customDomain = customDomainService.attachCustomDomain(appUser, gatewayId, request.hostname());
        return ApiResponse.success(toResponse(customDomain));
    }

    @GetMapping("/gateways/{gatewayId}/custom-domains")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<List<CustomDomainResponse>> listCustomDomainsForGateway(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID gatewayId
    ) {
        List<CustomDomainResponse> responses = customDomainService
                .listCustomDomainsForGateway(appUserPrincipal.getId(), gatewayId)
                .stream()
                .map(this::toResponse)
                .toList();
        return ApiResponse.success(responses);
    }

    @GetMapping("/custom-domains")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<PaginationResponse<CustomDomainResponse>> listCustomDomains(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<CustomDomainResponse> customDomains = customDomainService
                .listCustomDomains(appUserPrincipal.getId(), page, size)
                .map(this::toResponse);
        return ApiResponse.success(paginationMapper.toResponse(customDomains));
    }

    @GetMapping("/custom-domains/{id}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<CustomDomainResponse> getCustomDomainById(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID id
    ) {
        CustomDomain customDomain = customDomainService.getCustomDomainById(appUserPrincipal.getId(), id);
        return ApiResponse.success(toResponse(customDomain));
    }

    @PostMapping("/custom-domains/{id}/verification")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<CustomDomainResponse> initiateCustomDomainVerification(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID id
    ) {
        CustomDomain customDomain = customDomainService.initiateCustomDomainVerification(appUserPrincipal.getId(), id);
        return ApiResponse.success(toResponse(customDomain));
    }

    @DeleteMapping("/custom-domains/{id}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Map<String, String>> detachCustomDomain(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID id
    ) {
        customDomainService.detachCustomDomain(appUserPrincipal.getId(), id);
        return ApiResponse.success(Map.of("message", "Custom domain detached successfully"));
    }

    private CustomDomainResponse toResponse(CustomDomain customDomain) {
        String gatewayHostname = gatewayCertificateService.buildHostname(customDomain.getGateway());
        return customDomainMapper.toResponse(customDomain, gatewayHostname, gatewayNetworkProperties.publicIp());
    }
}
