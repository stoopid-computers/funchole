package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.config.CloudModeProperties;
import com.funchole.backend.controlplane.config.SecurityProperties;
import com.funchole.backend.controlplane.dto.GatewayCreateRequest;
import com.funchole.backend.controlplane.dto.GatewayUpdateRequest;
import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.constant.PackageLimitKey;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.event.GatewayCertificateProvisionRequested;
import com.funchole.backend.controlplane.repository.FlowRepository;
import com.funchole.backend.controlplane.repository.GatewayRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GatewayService {
    private static final String UNIQUE_KEY_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int UNIQUE_KEY_LENGTH = 6;
    private static final int UNIQUE_KEY_MAX_ATTEMPTS = 20;

    private final GatewayRepository gatewayRepository;
    private final FlowRepository flowRepository;
    private final DomainService domainService;
    private final GatewayCertificateService gatewayCertificateService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PackageLimitService packageLimitService;
    private final CloudModeProperties cloudModeProperties;
    private final SecurityProperties securityProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public GatewayService(
            GatewayRepository gatewayRepository,
            FlowRepository flowRepository,
            DomainService domainService,
            GatewayCertificateService gatewayCertificateService,
            ApplicationEventPublisher applicationEventPublisher,
            PackageLimitService packageLimitService,
            CloudModeProperties cloudModeProperties,
            SecurityProperties securityProperties
    ) {
        this.gatewayRepository = gatewayRepository;
        this.flowRepository = flowRepository;
        this.domainService = domainService;
        this.gatewayCertificateService = gatewayCertificateService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.packageLimitService = packageLimitService;
        this.cloudModeProperties = cloudModeProperties;
        this.securityProperties = securityProperties;
    }

    public Page<Gateway> listGateways(UUID appUserId, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page - 1, 0),
                Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, Gateway::getCreatedAt)
        );
        return gatewayRepository.findAllByAppUser_Id(appUserId, pageable);
    }

    public Gateway getGatewayById(UUID appUserId, UUID gatewayId) {
        return gatewayRepository.findByIdAndAppUser_Id(gatewayId, appUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Gateway not found: " + gatewayId));
    }

    @Transactional
    public Gateway createGateway(AppUser appUser, GatewayCreateRequest request) {
        packageLimitService.enforce(appUser.getId(), PackageLimitKey.MAX_GATEWAYS,
                gatewayRepository.countByAppUser_Id(appUser.getId()));
        AppDomain appDomain = resolveDomainForNewGateway(appUser, request.appDomainId());

        return provisionGateway(appUser, appDomain, request.name(), request.description(), request.status());
    }

    /**
     * A non-admin user (self-registered under cloud mode) can never own a
     * domain of their own (see {@code DomainService.createDomain}), so their
     * Gateway lands on a randomly chosen one of the admin's verified domains
     * instead of a caller-supplied one - covers both their initial
     * auto-provisioned default Gateway ({@code CloudSignupService}) and any
     * later one, uniformly. The admin (or any user when cloud mode is off)
     * keeps today's behavior: a caller-supplied, ownership-checked {@code
     * appDomainId} is required.
     */
    private AppDomain resolveDomainForNewGateway(AppUser appUser, UUID requestedDomainId) {
        if (cloudModeProperties.enabled() && !isBootstrapAdmin(appUser)) {
            return domainService.pickRandomVerifiedDomain();
        }
        if (requestedDomainId == null) {
            throw new IllegalArgumentException("appDomainId is required.");
        }
        AppDomain appDomain = domainService.getDomainById(appUser.getId(), requestedDomainId);
        validateVerifiedDomain(appDomain);
        return appDomain;
    }

    private boolean isBootstrapAdmin(AppUser appUser) {
        return securityProperties.bootstrapUser().username().equalsIgnoreCase(appUser.getUsername());
    }

    private Gateway provisionGateway(AppUser appUser, AppDomain appDomain, String name, String description, GatewayStatus status) {
        String uniqueKey = generateUniqueKey();

        Gateway gateway = Gateway.create(
                appUser,
                appDomain,
                name,
                uniqueKey,
                description,
                status
        );

        Gateway savedGateway = gatewayRepository.save(gateway);
        GatewayCertificateService.CertificateSyncResult certificateSyncResult =
                gatewayCertificateService.ensureCertificate(savedGateway);
        publishProvisioningIfRequired(certificateSyncResult);
        return savedGateway;
    }

    @Transactional
    public Gateway updateGateway(AppUser appUser, UUID gatewayId, GatewayUpdateRequest request) {
        Gateway gateway = getGatewayById(appUser.getId(), gatewayId);
        // Cloud users own no domain (see resolveDomainForNewGateway), so renaming
        // must not demand one: they stay on the domain they were assigned.
        AppDomain appDomain;
        if (cloudModeProperties.enabled() && !isBootstrapAdmin(appUser)) {
            appDomain = gateway.getAppDomain();
        } else {
            if (request.appDomainId() == null) {
                throw new IllegalArgumentException("appDomainId is required.");
            }
            appDomain = domainService.getDomainById(appUser.getId(), request.appDomainId());
            validateVerifiedDomain(appDomain);
        }

        gateway.update(
                appDomain,
                request.name(),
                gateway.getUniqueKey(),
                request.description(),
                request.status()
        );

        Gateway savedGateway = gatewayRepository.save(gateway);
        GatewayCertificateService.CertificateSyncResult certificateSyncResult =
                gatewayCertificateService.ensureCertificate(savedGateway);
        publishProvisioningIfRequired(certificateSyncResult);
        return savedGateway;
    }

    @Transactional
    public void deleteGateway(UUID appUserId, UUID gatewayId) {
        Gateway gateway = getGatewayById(appUserId, gatewayId);
        // flows.gateway_id has no foreign key, so deleting would silently orphan them.
        if (flowRepository.existsByGateway_IdAndDeletedAtIsNull(gatewayId)) {
            throw new IllegalStateException("This entry point still has workflows. Delete or move them first.");
        }
        gatewayRepository.delete(gateway);
    }

    private String generateUniqueKey() {
        for (int attempt = 0; attempt < UNIQUE_KEY_MAX_ATTEMPTS; attempt++) {
            String uniqueKey = randomAlphanumericKey();
            if (!gatewayRepository.existsByUniqueKey(uniqueKey)) {
                return uniqueKey;
            }
        }

        throw new IllegalStateException("Unable to generate a unique gateway key");
    }

    private String randomAlphanumericKey() {
        StringBuilder builder = new StringBuilder(UNIQUE_KEY_LENGTH);
        for (int index = 0; index < UNIQUE_KEY_LENGTH; index++) {
            int randomIndex = secureRandom.nextInt(UNIQUE_KEY_CHARACTERS.length());
            builder.append(UNIQUE_KEY_CHARACTERS.charAt(randomIndex));
        }
        return builder.toString();
    }

    private void publishProvisioningIfRequired(GatewayCertificateService.CertificateSyncResult certificateSyncResult) {
        if (certificateSyncResult.provisioningRequired()) {
            applicationEventPublisher.publishEvent(
                    new GatewayCertificateProvisionRequested(certificateSyncResult.certificate().getId())
            );
        }
    }

    private void validateVerifiedDomain(AppDomain appDomain) {
        if (appDomain.getStatus() != DomainStatus.VERIFIED) {
            throw new IllegalArgumentException("Gateway can only be created for a verified domain");
        }
    }
}
