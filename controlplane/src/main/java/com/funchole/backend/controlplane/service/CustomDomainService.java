package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.CustomDomainStatus;
import com.funchole.backend.controlplane.constant.PackageLimitKey;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.CustomDomain;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.repository.CustomDomainRepository;
import com.funchole.backend.controlplane.repository.GatewayRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.xbill.DNS.Lookup;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.TXTRecord;
import org.xbill.DNS.TextParseException;
import org.xbill.DNS.Type;

/**
 * Lets an end user attach their own hostname (subdomain via CNAME, or an
 * apex domain via A record) to one of their own Gateways - unlike
 * {@link DomainService}'s admin-only base domains, any user may attach a
 * custom domain to a Gateway they own, subject to the
 * {@code MAX_CUSTOM_DOMAINS} package limit. Verification is TXT-only,
 * deliberately mirroring {@code DomainService}'s own DNS-check algorithm
 * rather than sharing it - see that class for why duplication over
 * extraction is this codebase's preferred trade-off here.
 */
@Service
public class CustomDomainService {
    private static final Logger logger = LoggerFactory.getLogger(CustomDomainService.class);

    private final CustomDomainRepository customDomainRepository;
    private final GatewayRepository gatewayRepository;
    private final PackageLimitService packageLimitService;
    private final CustomDomainCertificateService customDomainCertificateService;

    public CustomDomainService(
            CustomDomainRepository customDomainRepository,
            GatewayRepository gatewayRepository,
            PackageLimitService packageLimitService,
            CustomDomainCertificateService customDomainCertificateService
    ) {
        this.customDomainRepository = customDomainRepository;
        this.gatewayRepository = gatewayRepository;
        this.packageLimitService = packageLimitService;
        this.customDomainCertificateService = customDomainCertificateService;
    }

    @Transactional
    public CustomDomain attachCustomDomain(AppUser appUser, UUID gatewayId, String hostname) {
        Gateway gateway = gatewayRepository.findByIdAndAppUser_Id(gatewayId, appUser.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Gateway not found: " + gatewayId));
        packageLimitService.enforce(appUser.getId(), PackageLimitKey.MAX_CUSTOM_DOMAINS,
                customDomainRepository.countByAppUser_Id(appUser.getId()));

        // The unique index would reject this too, but as an unhandled 500.
        if (customDomainRepository.existsByHostname(hostname.toLowerCase())) {
            throw new IllegalStateException("That domain is already connected to a FuncHole account.");
        }

        CustomDomain customDomain = CustomDomain.create(
                gateway, appUser, hostname.toLowerCase(), generateVerificationCode());
        return customDomainRepository.save(customDomain);
    }

    public Page<CustomDomain> listCustomDomains(UUID appUserId, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page - 1, 0),
                Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, "createdAt")
        );
        return customDomainRepository.findAllByAppUser_Id(appUserId, pageable);
    }

    public List<CustomDomain> listCustomDomainsForGateway(UUID appUserId, UUID gatewayId) {
        gatewayRepository.findByIdAndAppUser_Id(gatewayId, appUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Gateway not found: " + gatewayId));
        return customDomainRepository.findAllByGateway_Id(gatewayId);
    }

    public CustomDomain getCustomDomainById(UUID appUserId, UUID customDomainId) {
        return customDomainRepository.findByIdAndAppUser_Id(customDomainId, appUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Custom domain not found: " + customDomainId));
    }

    @Transactional
    public CustomDomain initiateCustomDomainVerification(UUID appUserId, UUID customDomainId) {
        CustomDomain customDomain = getCustomDomainById(appUserId, customDomainId);

        if (customDomain.getStatus() == CustomDomainStatus.VERIFIED) {
            return customDomain;
        }

        String recordName = buildVerificationRecordName(customDomain);
        if (hasMatchingTxtRecord(recordName, customDomain.getVerificationCode())) {
            customDomain.markVerified();
            CustomDomain saved = customDomainRepository.save(customDomain);
            customDomainCertificateService.ensureCertificate(saved);
            return saved;
        }

        return customDomain;
    }

    @Transactional
    public void detachCustomDomain(UUID appUserId, UUID customDomainId) {
        CustomDomain customDomain = getCustomDomainById(appUserId, customDomainId);
        customDomainRepository.delete(customDomain);
    }

    private String generateVerificationCode() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String buildVerificationRecordName(CustomDomain customDomain) {
        return "funchole-" + customDomain.getId() + "." + customDomain.getHostname();
    }

    private boolean hasMatchingTxtRecord(String recordName, String expectedValue) {
        try {
            Lookup lookup = new Lookup(toAbsoluteName(recordName), Type.TXT);
            lookup.setResolver(buildResolver());
            lookup.setCache(null);

            Record[] records = lookup.run();
            if (records == null || records.length == 0) {
                logger.info("No TXT record found for '{}'", recordName);
                logger.info("TXT lookup result for '{}': {}", recordName, lookup.getErrorString());
                return false;
            }

            for (Record record : records) {
                if (!(record instanceof TXTRecord txtRecord)) {
                    continue;
                }

                String normalizedValue = normalizeTxtValue(txtRecord.getStrings());
                logger.info("TXT record for '{}': {}", recordName, normalizedValue);
                if (expectedValue.equals(normalizedValue)) {
                    return true;
                }
            }

            return false;
        } catch (TextParseException exception) {
            logger.warn("Invalid TXT lookup name '{}': {}", recordName, exception.getMessage());
            return false;
        } catch (Exception exception) {
            logger.warn("Failed to resolve TXT records for '{}': {}", recordName, exception.getMessage());
            return false;
        }
    }

    private Name toAbsoluteName(String recordName) throws TextParseException {
        return Name.fromString(recordName.endsWith(".") ? recordName : recordName + ".");
    }

    private SimpleResolver buildResolver() throws Exception {
        SimpleResolver resolver = new SimpleResolver();
        resolver.setTimeout(Duration.ofSeconds(3));
        return resolver;
    }

    private String normalizeTxtValue(List<String> values) {
        return values.stream()
                .map(String::trim)
                .reduce("", String::concat);
    }
}
