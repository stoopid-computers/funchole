package com.funchole.backend.runtime;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which execution path a tenant's functions take: the original shared Node
 * process ({@link #LEGACY}) or a per-tenant sandbox ({@link #SANDBOX}).
 *
 * <p>Driven by {@code RUNTIME_ISOLATION} (default {@code legacy}) and an
 * optional {@code RUNTIME_ISOLATION_TENANTS} allow-list used to canary the
 * sandbox for a few tenants before it becomes the default. An unknown value
 * fails fast rather than silently falling back to the weaker mode.
 */
public enum IsolationMode {
    LEGACY,
    SANDBOX;

    public static IsolationMode parseDefault(String value) {
        if (value == null || value.isBlank()) {
            return LEGACY;
        }
        return switch (value.trim().toLowerCase()) {
            case "legacy" -> LEGACY;
            case "sandbox" -> SANDBOX;
            default -> throw new IllegalArgumentException("RUNTIME_ISOLATION must be 'legacy' or 'sandbox', got: " + value);
        };
    }

    public static Set<String> parseAllowList(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The allow-list wins for the tenants on it (they get the sandbox even
     * while the default is legacy); everyone else gets the default.
     */
    public static IsolationMode resolve(IsolationMode defaultMode, Set<String> sandboxTenants, String tenantId) {
        if (tenantId != null && sandboxTenants.contains(tenantId)) {
            return SANDBOX;
        }
        return defaultMode;
    }

    /**
     * Mixing modes in one runtime means the shared Node process (legacy) runs next to the
     * launcher's container access. Tenant code in that process could use it to escape, which defeats
     * the sandbox for everyone. So it is refused unless {@code allowMixed} is set deliberately.
     */
    public static void validate(IsolationMode defaultMode, Set<String> sandboxTenants, boolean allowMixed) {
        boolean mixed = defaultMode == LEGACY && !sandboxTenants.isEmpty();
        if (mixed && !allowMixed) {
            throw new IllegalStateException(
                    "RUNTIME_ISOLATION=legacy with RUNTIME_ISOLATION_TENANTS runs unsandboxed tenant code beside the sandbox "
                            + "launcher's container access. Run a separate sandbox runtime instead, or set "
                            + "RUNTIME_ISOLATION_ALLOW_MIXED=true if you accept that risk (test environments only).");
        }
    }
}
