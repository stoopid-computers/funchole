package com.funchole.backend.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.funchole.backend.controlplane.config.SecurityProperties;
import org.junit.jupiter.api.Test;

class EncryptionServiceTests {

    private final EncryptionService encryptionService =
            new EncryptionService(new SecurityProperties(null, null, new SecurityProperties.ApiKey("test-encryption-secret")));

    @Test
    void decryptsBackToTheOriginalPlaintext() {
        String encrypted = encryptionService.encrypt("fh_mcp_abc123");

        assertThat(encryptionService.decrypt(encrypted)).isEqualTo("fh_mcp_abc123");
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        // Confirms the IV is actually randomized per call, not reused -
        // a fixed IV would make identical keys distinguishable at rest.
        String first = encryptionService.encrypt("fh_mcp_abc123");
        String second = encryptionService.encrypt("fh_mcp_abc123");

        assertThat(first).isNotEqualTo(second);
    }
}
