package com.funchole.backend.controlplane.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row of "recent requests": enough to say when, which page, and whether
 * it worked. Deliberately carries no input or result, which can hold a
 * visitor's headers, cookies and personal data.
 */
public record InvocationActivityResponse(
        UUID invocationId,
        UUID flowId,
        String flowKey,
        String flowName,
        String status,
        String kind,
        String source,
        Integer httpStatus,
        OffsetDateTime createdAt,
        Long durationMs
) {
}
