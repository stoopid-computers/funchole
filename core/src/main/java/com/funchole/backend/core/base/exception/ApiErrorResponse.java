package com.funchole.backend.core.base.exception;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code code} is a stable, machine-readable reason (for example {@code
 * QUOTA_EXCEEDED}) so clients can branch on it instead of parsing the
 * human-readable {@code message}, which may change.
 */
public record ApiErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<String> details,
        String code
) {
}
