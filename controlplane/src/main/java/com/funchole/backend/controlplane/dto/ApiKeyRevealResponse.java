package com.funchole.backend.controlplane.dto;

import java.util.UUID;

/**
 * Returned by the reveal endpoint - the decrypted raw key, viewable again
 * any time after creation (unlike {@link ApiKeyCreateResponse}, which used
 * to be the only place it ever appeared).
 */
public record ApiKeyRevealResponse(
        UUID id,
        String rawKey
) {
}
