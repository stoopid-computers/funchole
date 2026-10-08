package com.funchole.backend.controlplane.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * {@code admin} is the platform admin (the bootstrap user) and {@code
 * cloudMode} is whether this deployment is the hosted cloud product: the UI
 * uses them to hide screens an ordinary cloud user cannot use (for example
 * the domain registry) instead of leading them into a dead end.
 */
public record ProfileResponse(
        UUID id,
        String username,
        String email,
        String fullName,
        OffsetDateTime createdAt,
        boolean admin,
        boolean cloudMode
) {
}
