package com.funchole.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Java driver -> docker/sandbox/launch.sh -> a real locked-down container. Skipped when Docker or
 * the image ({@code docker build -f docker/sandbox/Dockerfile -t funchole-sandbox:dev .}) is missing.
 */
class DockerSandboxIntegrationTest {

    private static final Path LAUNCHER = Path.of("..", "docker", "sandbox", "launch.sh").toAbsolutePath().normalize();

    @TempDir
    Path artifacts;

    private SandboxNodeExecutor executor;

    private static boolean dockerReady() {
        try {
            Process inspect = new ProcessBuilder("docker", "image", "inspect", "funchole-sandbox:dev")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return inspect.waitFor(20, TimeUnit.SECONDS) && inspect.exitValue() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    @BeforeEach
    void start() {
        assumeTrue(dockerReady(), "Docker or the funchole-sandbox:dev image is not available");
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("SANDBOX_ARTIFACT_VOLUME", artifacts.toString());
        environment.put("SANDBOX_ARTIFACT_MOUNT", "/artifacts");
        // Credentials that must never reach a sandbox, even though the launcher shares this environment.
        environment.put("S3_ARTIFACT_SECRET_KEY", "must-not-leak");
        executor = new SandboxNodeExecutor(new DockerSandboxLauncher(LAUNCHER, environment),
                new SandboxConfig(4, Duration.ofMinutes(5), 1000, Duration.ofSeconds(30)));
    }

    @AfterEach
    void stop() {
        if (executor != null) executor.close();
    }

    private NodeExecutionResult run(UUID tenant, String artifactName, String source) throws Exception {
        Files.writeString(artifacts.resolve(artifactName), source);
        NodeExecutionRequest request = new NodeExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Path.of("/artifacts", artifactName), "handler", "{}", Map.of("TENANT_VALUE", "visible-to-its-own-tenant"), List.of(), tenant);
        return executor.execute(request, log -> { }).toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    @Test
    void runsAFunctionInsideTheContainerWithOnlyItsOwnEnvironment() throws Exception {
        NodeExecutionResult result = run(UUID.randomUUID(), "env.mjs", """
                export async function handler() {
                  return { own: process.env.TENANT_VALUE, platform: process.env.S3_ARTIFACT_SECRET_KEY ?? null,
                           uid: process.getuid() };
                }
                """);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("\"own\":\"visible-to-its-own-tenant\"").contains("\"platform\":null").contains("\"uid\":10001");
    }

    @Test
    void theContainerHasNoNetworkAndAReadOnlyFilesystem() throws Exception {
        NodeExecutionResult result = run(UUID.randomUUID(), "probe.mjs", """
                import net from "node:net";
                import { writeFileSync } from "node:fs";
                export async function handler() {
                  const online = await new Promise((resolve) => {
                    const socket = net.connect({ host: "1.1.1.1", port: 443 });
                    socket.setTimeout(2000, () => { socket.destroy(); resolve(false); });
                    socket.once("connect", () => { socket.destroy(); resolve(true); });
                    socket.once("error", () => resolve(false));
                  });
                  let wroteApp = true;
                  try { writeFileSync("/app/x", "x"); } catch { wroteApp = false; }
                  return { online, wroteApp };
                }
                """);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("{\"online\":false,\"wroteApp\":false}");
    }
}
