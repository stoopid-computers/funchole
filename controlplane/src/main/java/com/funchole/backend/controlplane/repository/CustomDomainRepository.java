package com.funchole.backend.controlplane.repository;

import com.funchole.backend.certificate.CertificateStatus;
import com.funchole.backend.controlplane.constant.CustomDomainStatus;
import com.funchole.backend.controlplane.entity.CustomDomain;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomDomainRepository extends JpaRepository<CustomDomain, UUID> {

    // "gateway.appDomain" (not just "gateway") - GatewayCertificateService.buildHostname()
    // reads gateway.getAppDomain().getDomainName(), and that response mapping happens
    // after this method's own transaction/session has closed (see CustomDomainController),
    // so the nested relation must be eagerly fetched here too, exactly like
    // GatewayCertificateRepository's own finders already do for the same reason.
    @EntityGraph(attributePaths = { "gateway", "gateway.appDomain" })
    List<CustomDomain> findAllByGateway_Id(UUID gatewayId);

    @EntityGraph(attributePaths = { "gateway", "gateway.appDomain" })
    Page<CustomDomain> findAllByAppUser_Id(UUID appUserId, Pageable pageable);

    @EntityGraph(attributePaths = { "gateway", "gateway.appDomain" })
    Optional<CustomDomain> findByIdAndAppUser_Id(UUID id, UUID appUserId);

    long countByAppUser_Id(UUID appUserId);

    // Scoped to VERIFIED domains only - a freshly-attached CustomDomain also
    // starts with certStatus=PENDING (see CustomDomain.create()), but must
    // never be picked up here before DNS ownership is actually verified;
    // only CustomDomainService.initiateCustomDomainVerification's explicit
    // ensureCertificate() call (post-verification) may provision a cert for
    // a still-PENDING (unverified) domain.
    @EntityGraph(attributePaths = { "gateway", "gateway.appDomain" })
    List<CustomDomain> findByStatusAndCertStatusIn(CustomDomainStatus status, Collection<CertificateStatus> certStatuses);

    @EntityGraph(attributePaths = { "gateway" })
    List<CustomDomain> findByCertStatus(CertificateStatus status);

    @EntityGraph(attributePaths = { "gateway" })
    List<CustomDomain> findByCertStatusAndCertExpiresAtBefore(CertificateStatus status, OffsetDateTime expiresAtBefore);
}
