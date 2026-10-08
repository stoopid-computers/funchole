package com.funchole.backend.runtime;

import java.time.Duration;
import java.util.Map;

/** Limits of the sandbox pool. All values come from {@code SANDBOX_*} environment settings. */
record SandboxConfig(int maxSandboxes, Duration idleTtl, int recycleAfterInvocations, Duration executionTimeout) {

    SandboxConfig {
        if (maxSandboxes < 1) throw new IllegalArgumentException("SANDBOX_MAX_SANDBOXES must be at least 1");
        if (recycleAfterInvocations < 1) throw new IllegalArgumentException("SANDBOX_RECYCLE_AFTER must be at least 1");
    }

    static SandboxConfig from(Map<String, String> environment) {
        return new SandboxConfig(
                intOf(environment, "SANDBOX_MAX_SANDBOXES", 8),
                Duration.ofSeconds(intOf(environment, "SANDBOX_IDLE_TTL_SECONDS", 300)),
                intOf(environment, "SANDBOX_RECYCLE_AFTER", 1000),
                Duration.ofSeconds(intOf(environment, "SANDBOX_EXEC_TIMEOUT_SECONDS", 60)));
    }

    private static int intOf(Map<String, String> environment, String name, int fallback) {
        String value = environment.get(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }
}
