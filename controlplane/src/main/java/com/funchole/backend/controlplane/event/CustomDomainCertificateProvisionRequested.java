package com.funchole.backend.controlplane.event;

import java.util.UUID;

public record CustomDomainCertificateProvisionRequested(
        UUID customDomainId
) {
}
