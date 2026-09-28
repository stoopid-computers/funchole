package com.funchole.backend.controlplane.dto;

import com.funchole.backend.certificate.CertificateStatus;
import com.funchole.backend.controlplane.constant.CustomDomainStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CustomDomainResponse(
        UUID id,
        UUID gatewayId,
        String gatewayHostname,
        String hostname,
        CustomDomainStatus status,
        String verificationCode,
        CertificateStatus certStatus,
        String gatewayPublicIp,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
