package com.funchole.backend.controlplane.entity;

import com.funchole.backend.certificate.CertificateProvider;
import com.funchole.backend.certificate.CertificateStatus;
import com.funchole.backend.controlplane.constant.CustomDomainStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "custom_domains")
public class CustomDomain {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "gateway_id", nullable = false)
    private Gateway gateway;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "app_user_id", nullable = false)
    private AppUser appUser;

    @Column(nullable = false, length = 255)
    private String hostname;

    @Column(name = "verification_code", nullable = false, length = 255)
    private String verificationCode;

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private CustomDomainStatus status;

    @Column(name = "cert_provider", nullable = false, length = 100)
    @Enumerated(EnumType.STRING)
    private CertificateProvider certProvider;

    @Column(name = "cert_status", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private CertificateStatus certStatus;

    @Column(name = "cert_secret_ref", length = 255)
    private String certSecretRef;

    @Column(name = "cert_issued_at")
    private OffsetDateTime certIssuedAt;

    @Column(name = "cert_expires_at")
    private OffsetDateTime certExpiresAt;

    @Column(name = "cert_renewed_at")
    private OffsetDateTime certRenewedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public UUID getId() {
        return id;
    }

    public Gateway getGateway() {
        return gateway;
    }

    public AppUser getAppUser() {
        return appUser;
    }

    public String getHostname() {
        return hostname;
    }

    public String getVerificationCode() {
        return verificationCode;
    }

    public CustomDomainStatus getStatus() {
        return status;
    }

    public CertificateProvider getCertProvider() {
        return certProvider;
    }

    public CertificateStatus getCertStatus() {
        return certStatus;
    }

    public String getCertSecretRef() {
        return certSecretRef;
    }

    public OffsetDateTime getCertIssuedAt() {
        return certIssuedAt;
    }

    public OffsetDateTime getCertExpiresAt() {
        return certExpiresAt;
    }

    public OffsetDateTime getCertRenewedAt() {
        return certRenewedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void markVerified() {
        this.status = CustomDomainStatus.VERIFIED;
        this.updatedAt = OffsetDateTime.now();
    }

    public void markCertActive(OffsetDateTime issuedAt, OffsetDateTime expiresAt) {
        this.certStatus = CertificateStatus.ACTIVE;
        this.certIssuedAt = issuedAt;
        this.certExpiresAt = expiresAt;
        this.certRenewedAt = issuedAt;
        this.updatedAt = OffsetDateTime.now();
    }

    public void markCertFailed() {
        this.certStatus = CertificateStatus.FAILED;
        this.updatedAt = OffsetDateTime.now();
    }

    public void updateCertProvisioningTarget(CertificateProvider provider, String secretRef) {
        this.certProvider = provider;
        this.certSecretRef = secretRef;
        this.certStatus = CertificateStatus.PENDING;
        this.certIssuedAt = null;
        this.certExpiresAt = null;
        this.updatedAt = OffsetDateTime.now();
    }

    public static CustomDomain create(Gateway gateway, AppUser appUser, String hostname, String verificationCode) {
        CustomDomain customDomain = new CustomDomain();
        OffsetDateTime now = OffsetDateTime.now();
        customDomain.id = UUID.randomUUID();
        customDomain.gateway = gateway;
        customDomain.appUser = appUser;
        customDomain.hostname = hostname;
        customDomain.verificationCode = verificationCode;
        customDomain.status = CustomDomainStatus.PENDING;
        customDomain.certProvider = CertificateProvider.SELF_SIGNED;
        customDomain.certStatus = CertificateStatus.PENDING;
        customDomain.createdAt = now;
        customDomain.updatedAt = now;
        return customDomain;
    }
}
