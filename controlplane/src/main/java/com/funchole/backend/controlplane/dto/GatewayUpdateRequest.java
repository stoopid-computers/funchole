package com.funchole.backend.controlplane.dto;

import com.funchole.backend.controlplane.constant.GatewayStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record GatewayUpdateRequest(
        @Schema(defaultValue = "Primary Gateway", example = "Primary Gateway")
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must be at most 100 characters")
        String name,

        @Schema(defaultValue = "Primary public gateway", example = "Primary public gateway")
        @Nullable
        @Size(max = 1000, message = "Description must be at most 1000 characters")
        String description,

        // Ignored for cloud users, whose gateway stays on its assigned domain.
        @Nullable
        UUID appDomainId,

        @NotNull(message = "Status is required")
        GatewayStatus status
) {
}
