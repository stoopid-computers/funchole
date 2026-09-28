package com.funchole.backend.controlplane.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CustomDomainCreateRequest(
    @Schema(defaultValue = "hello.example.com", example = "hello.example.com")
    @NotBlank(message = "Hostname is required")
    @Pattern(
            regexp = "^(?=.{1,253}$)(?!-)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.)+[A-Za-z]{2,63}$",
            message = "Invalid hostname"
    )
    String hostname
) {
}
