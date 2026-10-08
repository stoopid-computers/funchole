package com.funchole.backend.controlplane.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Visitor traffic over the last 24 hours, overall and per page. */
public record ActivitySummaryResponse(
        long requests24h,
        long failed24h,
        OffsetDateTime lastRequestAt,
        List<PageActivity> pages
) {

    public record PageActivity(
            UUID flowId,
            String flowKey,
            String flowName,
            OffsetDateTime lastRequestAt,
            long requests24h,
            long failed24h
    ) {
    }
}
