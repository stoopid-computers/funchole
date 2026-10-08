package com.funchole.backend.controlplane.dto;

import java.util.List;

/**
 * The user's plan and how much of each limit they have used. {@code limit}
 * is {@code null} when unlimited, and {@code remaining} is then {@code null}
 * too. On a self-hosted install ({@code cloudMode} false) nothing is limited.
 */
public record PackageUsageResponse(
        boolean cloudMode,
        String packageKey,
        String packageName,
        List<Limit> limits
) {

    public record Limit(String key, Integer limit, long used, Long remaining) {
    }
}
