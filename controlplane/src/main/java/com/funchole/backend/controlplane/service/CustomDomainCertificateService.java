package com.funchole.backend.controlplane.service;

import com.funchole.backend.certificate.CertificateProvider;
import com.funchole.backend.certificate.CertificateReference;
import com.funchole.backend.certificate.CertificateRequest;
import com.funchole.backend.certificate.CertificateStatus;
import com.funchole.backend.certificate.GeneratedCertificate;
import com.funchole.backend.certificate.generator.CertificateGenerator;
import com.funchole.backend.certificate.store.CertificateStore;
import com.funchole.backend.controlplane.config.CertificateProperties;
import com.funchole.backend.controlplane.constant.CustomDomainStatus;
import com.funchole.backend.controlplane.entity.CustomDomain;
import com.funchole.backend.controlplane.event.CustomDomainCertificateProvisionRequested;
import com.funchole.backend.controlplane.repository.CustomDomainRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Certificate issuance for {@link CustomDomain}s - structurally identical to
 * {@code GatewayCertificateService}, kept as a separate service because the
 * two entities have different field names/repositories with no code-sharing
 * benefit beyond the three scheduled jobs' identical shape.
 */
@Service
public class CustomDomainCertificateService {
    private static final Logger logger = LoggerFactory.getLogger(CustomDomainCertificateService.class);

    private final CustomDomainRepository customDomainRepository;
    private final CertificateGenerator certificateGenerator;
    private final CertificateStore certificateStore;
    private final CertificateProperties certificateProperties;
    private final ApplicationEventPublisher applicationEventPublisher;

    public CustomDomainCertificateService(
            CustomDomainRepository customDomainRepository,
            CertificateGenerator certificateGenerator,
            CertificateStore certificateStore,
            CertificateProperties certificateProperties,
            ApplicationEventPublisher applicationEventPublisher
    ) {
        this.customDomainRepository = customDomainRepository;
        this.certificateGenerator = certificateGenerator;
        this.certificateStore = certificateStore;
        this.certificateProperties = certificateProperties;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional
    public void ensureCertificate(CustomDomain customDomain) {
        CertificateProvider provider = certificateProperties.provider();
        String secretRef = buildSecretRef(customDomain);

        boolean secretRefChanged = !secretRef.equals(customDomain.getCertSecretRef());
        boolean providerChanged = provider != customDomain.getCertProvider();
        boolean provisioningRequired =
                secretRefChanged || providerChanged || customDomain.getCertStatus() != CertificateStatus.ACTIVE;

        if (provisioningRequired) {
            customDomain.updateCertProvisioningTarget(provider, secretRef);
            CustomDomain saved = customDomainRepository.save(customDomain);
            applicationEventPublisher.publishEvent(new CustomDomainCertificateProvisionRequested(saved.getId()));
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProvisionRequested(CustomDomainCertificateProvisionRequested event) {
        provisionCertificate(event.customDomainId());
    }

    @Scheduled(fixedDelayString = "${app.certificate.retry-delay-ms:60000}")
    public void retryPendingCertificates() {
        // VERIFIED only - a freshly-attached, never-verified domain also
        // starts at certStatus=PENDING (see CustomDomain.create()) and must
        // not have this sweep issue it a certificate before DNS ownership
        // is actually proven.
        List<CustomDomain> customDomains = customDomainRepository.findByStatusAndCertStatusIn(
                CustomDomainStatus.VERIFIED, List.of(CertificateStatus.PENDING, CertificateStatus.FAILED)
        );

        for (CustomDomain customDomain : customDomains) {
            provisionCertificate(customDomain.getId());
        }
    }

    @Scheduled(fixedDelayString = "${app.certificate.retry-delay-ms:60000}", initialDelayString = "${app.certificate.retry-delay-ms:60000}")
    public void renewExpiringCertificates() {
        OffsetDateTime renewalThreshold = OffsetDateTime.now().plusDays(certificateProperties.renewalWindowDays());
        List<CustomDomain> customDomains = customDomainRepository.findByCertStatusAndCertExpiresAtBefore(
                CertificateStatus.ACTIVE, renewalThreshold
        );

        for (CustomDomain customDomain : customDomains) {
            logger.info(
                    "Certificate for custom domain {} hostname {} expires at {}, within the {}-day renewal window; renewing",
                    customDomain.getId(),
                    customDomain.getHostname(),
                    customDomain.getCertExpiresAt(),
                    certificateProperties.renewalWindowDays()
            );
            provisionCertificate(customDomain.getId());
        }
    }

    @Scheduled(fixedDelayString = "${app.certificate.retry-delay-ms:60000}", initialDelayString = "${app.certificate.retry-delay-ms:60000}")
    public void reconcileActiveCertificates() {
        List<CustomDomain> customDomains = customDomainRepository.findByCertStatus(CertificateStatus.ACTIVE);

        for (CustomDomain customDomain : customDomains) {
            if (customDomain.getCertSecretRef() == null || customDomain.getCertSecretRef().isBlank()) {
                logger.warn(
                        "Active certificate for custom domain {} has no secret reference; reprovisioning",
                        customDomain.getId()
                );
                provisionCertificate(customDomain.getId());
                continue;
            }

            try {
                certificateStore.load(new CertificateReference(customDomain.getCertSecretRef()));
            } catch (Exception exception) {
                logger.warn(
                        "Certificate material missing for custom domain {} hostname {}; reprovisioning",
                        customDomain.getId(),
                        customDomain.getHostname(),
                        exception
                );
                provisionCertificate(customDomain.getId());
            }
        }
    }

    public void provisionCertificate(UUID customDomainId) {
        CustomDomain customDomain = customDomainRepository.findById(customDomainId).orElse(null);
        if (customDomain == null) {
            return;
        }

        try {
            GeneratedCertificate generatedCertificate = certificateGenerator.generate(new CertificateRequest(
                    customDomain.getHostname(),
                    List.of(customDomain.getHostname())
            ));
            certificateStore.save(
                    new CertificateReference(customDomain.getCertSecretRef()),
                    generatedCertificate.bundle()
            );
            customDomain.markCertActive(generatedCertificate.issuedAt(), generatedCertificate.expiresAt());
            customDomainRepository.save(customDomain);
        } catch (Exception exception) {
            customDomain.markCertFailed();
            customDomainRepository.save(customDomain);
            logger.error(
                    "Certificate provisioning failed for custom domain {} hostname {} provider {}",
                    customDomain.getId(),
                    customDomain.getHostname(),
                    customDomain.getCertProvider(),
                    exception
            );
        }
    }

    private String buildSecretRef(CustomDomain customDomain) {
        return "certificates/custom-domains/" + customDomain.getId();
    }
}
