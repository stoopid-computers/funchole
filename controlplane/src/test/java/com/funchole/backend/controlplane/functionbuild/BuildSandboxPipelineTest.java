package com.funchole.backend.controlplane.functionbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.funchole.backend.controlplane.functionbuild.process.ProcessResult;
import com.funchole.backend.controlplane.functionbuild.process.SandboxProcessExecutor;
import com.funchole.backend.controlplane.functionbuild.runtime.node.NodeRuntimeBuilder;
import com.funchole.backend.controlplane.functionbuild.runtime.staticsite.StaticRuntimeBuilder;
import com.funchole.backend.sandbox.manager.SandboxManager;
import com.funchole.backend.sandbox.protocol.SandboxManagerClient;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The real Node and static builders, running real npm inside the real sandbox, behind the same
 * {@code ProcessExecutor} seam the controlplane uses in production. Needs Docker and
 * {@code docker build -f docker/sandbox/Dockerfile.build -t funchole-build-sandbox:dev .}; skipped otherwise.
 * Dependencies are local ({@code file:}) so no package registry is involved.
 */
class BuildSandboxPipelineTest {

    private static final String TOKEN = "pipeline-test-token-0123456789";
    private static final Path LAUNCHER = Path.of("..", "docker", "sandbox", "build-launch.sh").toAbsolutePath().normalize();

    @TempDir
    Path temp;

    private SandboxManager manager;
    private SandboxProcessExecutor executor;

    private static boolean dockerReady() {
        try {
            Process inspect = new ProcessBuilder("docker", "image", "inspect", "funchole-build-sandbox:dev")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return inspect.waitFor(20, TimeUnit.SECONDS) && inspect.exitValue() == 0;
        } catch (Exception exception) {
            return false;
        }
    }

    @BeforeEach
    void start() throws Exception {
        assumeTrue(dockerReady(), "Docker or the funchole-build-sandbox:dev image is not available");
        manager = SandboxManager.start(Map.of(
                "SANDBOX_MANAGER_TOKEN", TOKEN, "SANDBOX_MANAGER_BIND", "127.0.0.1", "SANDBOX_MANAGER_PORT", "0",
                "SANDBOX_JOBS_DIR", Files.createDirectories(temp.resolve("jobs")).toString(),
                "SANDBOX_BUILD_LAUNCHER", LAUNCHER.toString(),
                "PATH", System.getenv("PATH"), "HOME", System.getenv().getOrDefault("HOME", "/tmp")));
        executor = new SandboxProcessExecutor(new SandboxManagerClient(URI.create("http://127.0.0.1:" + manager.port()), TOKEN));
    }

    @AfterEach
    void stop() {
        if (manager != null) manager.close();
    }

    private BuildWorkspace workspace(String runtimeType, String entrypoint, Map<String, String> files) throws Exception {
        Path root = Files.createDirectories(temp.resolve("ws-" + UUID.randomUUID()));
        for (var file : files.entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        return new BuildWorkspace(UUID.randomUUID(), root, entrypoint, "handler", runtimeType, null);
    }

    @Test
    void aNodeFunctionWithALocalDependencyBuildsIntoAnArtifactWithItsNodeModules() throws Exception {
        BuildWorkspace workspace = workspace("NODE", "index.mjs", Map.of(
                "index.mjs", "import { hello } from 'greeter'; export async function handler() { return hello(); }",
                "package.json", "{\"name\":\"fn\",\"version\":\"1.0.0\",\"dependencies\":{\"greeter\":\"file:./vendor/greeter\"}}",
                "vendor/greeter/package.json", "{\"name\":\"greeter\",\"version\":\"1.0.0\",\"type\":\"module\",\"main\":\"index.js\"}",
                "vendor/greeter/index.js", "export const hello = () => 'hi from greeter';"));
        List<String> stages = new ArrayList<>();

        try (PreparedArtifact artifact = new NodeRuntimeBuilder(executor).build(workspace, (stage, command, result) -> stages.add(stage + ":" + result.exitCode()))) {
            assertThat(stages).containsExactly("dependency-install:0");
            assertThat(artifact.artifactDirectory().resolve("node_modules/greeter/index.js")).exists();
            assertThat(artifact.artifactDirectory().resolve("index.mjs")).exists();
        }
    }

    @Test
    void aMaliciousPostinstallRunsButSeesNoSecretsAndCannotEscape() throws Exception {
        String attack = "env > /work/leaked-env.txt; id -u > /work/uid.txt; (cat /etc/shadow || echo denied) > /work/shadow.txt 2>&1; "
                + "(ls /var/run/docker.sock || echo no-socket) > /work/socket.txt 2>&1";
        BuildWorkspace workspace = workspace("NODE", "index.mjs", Map.of(
                "index.mjs", "export async function handler() { return 1; }",
                "package.json", "{\"name\":\"fn\",\"version\":\"1.0.0\",\"dependencies\":{\"evil\":\"file:./vendor/evil\"}}",
                "vendor/evil/package.json", "{\"name\":\"evil\",\"version\":\"1.0.0\",\"scripts\":{\"postinstall\":\"" + attack + "\"}}"));

        try (PreparedArtifact artifact = new NodeRuntimeBuilder(executor).build(workspace, BuildLogRecorder.NOOP)) {
            Path dir = artifact.artifactDirectory();
            // The attack ran inside the sandbox (its files came back), and found nothing worth having.
            String env = readIfExists(dir.resolve("node_modules/evil/leaked-env.txt"), dir.resolve("leaked-env.txt"));
            assertThat(env).isNotEmpty().doesNotContain("S3_ARTIFACT").doesNotContain("DB_PASSWORD").doesNotContain("JWT").doesNotContain(TOKEN);
            assertThat(readIfExists(dir.resolve("node_modules/evil/uid.txt"), dir.resolve("uid.txt")).trim()).isNotEqualTo("0");
            assertThat(readIfExists(dir.resolve("node_modules/evil/socket.txt"), dir.resolve("socket.txt"))).contains("no-socket");
        }
    }

    @Test
    void aStaticSiteInstallsThenBuildsInTheSameWorkspaceAndOnlyItsOutputComesBack() throws Exception {
        BuildWorkspace workspace = workspace("STATIC", "package.json", Map.of(
                "package.json", "{\"name\":\"site\",\"version\":\"1.0.0\",\"scripts\":{\"build\":\"mkdir -p dist && echo '<html>site</html>' > dist/index.html\"}}"));
        List<String> stages = new ArrayList<>();

        try (PreparedArtifact artifact = new StaticRuntimeBuilder(executor).build(workspace, (stage, command, result) -> stages.add(stage + ":" + result.exitCode()))) {
            assertThat(stages).containsExactly("dependency-install:0", "build:0");
            assertThat(Files.readString(artifact.artifactDirectory().resolve("index.html"))).contains("site");
        }
    }

    @Test
    void aFailingBuildScriptSurfacesAsABuildFailureWithItsOutput() throws Exception {
        BuildWorkspace workspace = workspace("STATIC", "package.json", Map.of(
                "package.json", "{\"name\":\"site\",\"version\":\"1.0.0\",\"scripts\":{\"build\":\"echo compile-error >&2; exit 2\"}}"));

        assertThatThrownBy(() -> new StaticRuntimeBuilder(executor).build(workspace, BuildLogRecorder.NOOP))
                .hasMessageContaining("build")
                .satisfies(error -> assertThat(String.valueOf(error)).containsAnyOf("compile-error", "exit"));
    }

    @Test
    void whenTheManagerIsGoneTheBuildFailsInsteadOfRunningLocally() throws Exception {
        BuildWorkspace workspace = workspace("NODE", "index.mjs", Map.of(
                "index.mjs", "export async function handler() { return 1; }",
                "package.json", "{\"name\":\"fn\",\"version\":\"1.0.0\",\"dependencies\":{}}"));
        manager.close();

        assertThatThrownBy(() -> new NodeRuntimeBuilder(executor).build(workspace, BuildLogRecorder.NOOP))
                .hasMessageContaining("isolated build environment");
    }

    private static String readIfExists(Path... candidates) throws Exception {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) return Files.readString(candidate);
        }
        return "";
    }
}
