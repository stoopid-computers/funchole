package com.funchole.backend.controlplane.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        Jwt jwt,
        BootstrapUser bootstrapUser,
        ApiKey apiKey
) {

    public record Jwt(
            @NotBlank String secret,
            @Min(60) long expirationSeconds
    ) {
    }

    public record BootstrapUser(
            @NotBlank String username,
            @NotBlank String password
    ) {
    }

    public record ApiKey(
            @NotBlank String encryptionSecret
    ) {
    }
}
