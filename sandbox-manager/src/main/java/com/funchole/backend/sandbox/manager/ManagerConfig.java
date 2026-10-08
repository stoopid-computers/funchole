package com.funchole.backend.sandbox.manager;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Settings for the manager, all from environment variables. */
record ManagerConfig(
        String bindAddress,
        int port,
        String token,
        Path jobsDir,
        Path buildLauncher,
        int maxJobs,
        int maxConcurrentRuns,
        Duration jobTtl,
        long maxUploadBytes,
        int maxOutputChars,
        int maxTimeoutSeconds,
        Map<String, String> launcherEnvironment
) {

    static final int MIN_TOKEN_LENGTH = 24;

    ManagerConfig {
        if (token == null || token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException("SANDBOX_MANAGER_TOKEN must be set to a secret of at least " + MIN_TOKEN_LENGTH + " characters");
        }
        if (jobsDir == null) {
            throw new IllegalArgumentException("SANDBOX_JOBS_DIR must be set (a directory the Docker daemon can bind-mount at the same path)");
        }
    }

    static ManagerConfig from(Map<String, String> env) {
        String jobsDir = env.get("SANDBOX_JOBS_DIR");
        Map<String, String> launcherEnvironment = new java.util.HashMap<>();
        env.forEach((key, value) -> {
            if (key.startsWith("BUILD_") || key.equals("PATH") || key.equals("HOME") || key.equals("DOCKER_HOST")) {
                launcherEnvironment.put(key, value);
            }
        });
        return new ManagerConfig(
                env.getOrDefault("SANDBOX_MANAGER_BIND", "0.0.0.0"),
                intOf(env, "SANDBOX_MANAGER_PORT", 7090),
                env.get("SANDBOX_MANAGER_TOKEN"),
                jobsDir == null || jobsDir.isBlank() ? null : Path.of(jobsDir).toAbsolutePath().normalize(),
                Path.of(env.getOrDefault("SANDBOX_BUILD_LAUNCHER", "/opt/funchole/sandbox/build-launch.sh")),
                intOf(env, "SANDBOX_MAX_JOBS", 8),
                intOf(env, "SANDBOX_MAX_CONCURRENT_RUNS", 2),
                Duration.ofMinutes(intOf(env, "SANDBOX_JOB_TTL_MINUTES", 30)),
                Long.parseLong(env.getOrDefault("SANDBOX_MAX_UPLOAD_BYTES", String.valueOf(512L * 1024 * 1024))),
                intOf(env, "SANDBOX_MAX_OUTPUT_CHARS", 1_000_000),
                intOf(env, "SANDBOX_MAX_TIMEOUT_SECONDS", 1800),
                Map.copyOf(launcherEnvironment));
    }

    private static int intOf(Map<String, String> env, String name, int fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }
}
