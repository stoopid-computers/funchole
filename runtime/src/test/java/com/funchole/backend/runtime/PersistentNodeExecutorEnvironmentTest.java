package com.funchole.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tenant code runs inside the Node process, so it must not inherit the runtime's own environment. */
class PersistentNodeExecutorEnvironmentTest {

    private static final Path SCRIPT_PATH = Path.of("node", "executor.mjs").toAbsolutePath();

    @TempDir
    Path artifacts;

    private PersistentNodeExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) executor.close();
    }

    private Map<String, String> runtimeLikeParentEnvironment() {
        Map<String, String> parent = new HashMap<>();
        parent.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        parent.put("HOME", System.getenv().getOrDefault("HOME", "/tmp"));
        parent.put("LANG", "C.UTF-8");
        parent.put("S3_ARTIFACT_ACCESS_KEY", "canary-access");
        parent.put("S3_ARTIFACT_SECRET_KEY", "canary-secret");
        parent.put("DB_PASSWORD", "canary-db");
        parent.put("BAO_TOKEN_FILE", "/openbao/canary");
        parent.put("RUNTIME_WORKER_SOCKET_PATH", "/tmp/funchole/runtime.sock");
        parent.put("JAVA_HOME", "/opt/java");
        return parent;
    }

    private NodeExecutionResult run(String source, Map<String, String> invocationEnvironment) throws Exception {
        Path artifact = artifacts.resolve("fn-" + UUID.randomUUID() + ".mjs");
        Files.writeString(artifact, source);
        NodeExecutionRequest request = new NodeExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                artifact, "handler", "{}", invocationEnvironment, List.of());
        return executor.execute(request, log -> { }).toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void tenantCodeSeesOnlyTheHarmlessBasicsNotTheRuntimesOwnEnvironment() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH, runtimeLikeParentEnvironment());

        NodeExecutionResult result = run("export async function handler() { return Object.keys(process.env).sort(); }", Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output())
                .contains("\"PATH\"")
                .doesNotContain("S3_ARTIFACT").doesNotContain("DB_PASSWORD").doesNotContain("BAO_")
                .doesNotContain("RUNTIME_WORKER").doesNotContain("JAVA_HOME");
    }

    @Test
    void theFunctionsOwnInjectedVariablesStillWorkExactlyAsBefore() throws Exception {
        executor = PersistentNodeExecutor.start("node", SCRIPT_PATH, runtimeLikeParentEnvironment());

        NodeExecutionResult result = run(
                "export async function handler() { return { own: process.env.MY_API_KEY, platform: process.env.S3_ARTIFACT_SECRET_KEY ?? null }; }",
                Map.of("MY_API_KEY", "tenant-value"));

        assertThat(result.output()).isEqualTo("{\"own\":\"tenant-value\",\"platform\":null}");
    }

    @Test
    void theAllowListKeepsOnlyTheNamedBasics() {
        Map<String, String> filtered = PersistentNodeExecutor.childEnvironment(runtimeLikeParentEnvironment());

        assertThat(filtered.keySet()).containsExactlyInAnyOrder("PATH", "HOME", "LANG");
    }
}
