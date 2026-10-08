package com.funchole.backend.sandbox.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.funchole.backend.sandbox.protocol.ExecResult;
import com.funchole.backend.sandbox.protocol.SandboxManagerClient;
import com.funchole.backend.sandbox.protocol.SandboxManagerException;
import com.funchole.backend.sandbox.protocol.SyncBack;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Client -> manager (HTTP) -> build-launch.sh -> a real locked-down container. Skipped without Docker or
 * the image ({@code docker build -f docker/sandbox/Dockerfile.build -t funchole-build-sandbox:dev .}).
 */
class BuildSandboxIntegrationTest {

    private static final String TOKEN = "integration-test-token-0123456789";
    private static final Path LAUNCHER = Path.of("..", "docker", "sandbox", "build-launch.sh").toAbsolutePath().normalize();

    @TempDir
    Path temp;

    private ManagerServer server;
    private SandboxManagerClient client;
    private Path workspace;

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
        // Platform secrets present in the manager's own launcher environment must never reach a build.
        Map<String, String> launcherEnvironment = Map.of(
                "PATH", System.getenv("PATH"), "HOME", System.getenv().getOrDefault("HOME", "/tmp"),
                "S3_ARTIFACT_SECRET_KEY", "must-not-leak", "DB_PASSWORD", "must-not-leak");
        ManagerConfig config = new ManagerConfig("127.0.0.1", 0, TOKEN, Files.createDirectories(temp.resolve("jobs")), LAUNCHER,
                4, 2, Duration.ofMinutes(5), 64L * 1024 * 1024, 100_000, 60, launcherEnvironment);
        server = new ManagerServer(config);
        server.start();
        client = new SandboxManagerClient(URI.create("http://127.0.0.1:" + server.port()), TOKEN);
        workspace = Files.createDirectories(temp.resolve("workspace"));
    }

    @AfterEach
    void stop() {
        if (server != null) server.close();
    }

    private ExecResult sh(String script, SyncBack sync) {
        return client.exec(workspace, List.of("sh", "-c", script), Duration.ofSeconds(60), sync);
    }

    @Test
    void runsACommandAndBringsTheChangedWorkspaceBack() throws Exception {
        Files.writeString(workspace.resolve("in.txt"), "hello");

        ExecResult result = sh("cat in.txt > out.txt && echo built", SyncBack.ALL);

        assertThat((!result.timedOut() && result.exitCode() != null && result.exitCode() == 0)).isTrue();
        assertThat(result.stdout()).contains("built");
        assertThat(Files.readString(workspace.resolve("out.txt"))).isEqualTo("hello");
    }

    @Test
    void keepsTheJobOpenAcrossCommandsSoNothingIsShuttledBetweenSteps() throws Exception {
        sh("echo step-one > state.txt", SyncBack.NONE);
        assertThat(workspace.resolve("state.txt")).doesNotExist(); // not brought back yet

        ExecResult second = sh("cat state.txt", SyncBack.ALL);

        assertThat(second.stdout()).contains("step-one");
        assertThat(workspace.resolve("state.txt")).exists();
    }

    @Test
    void reportsFailureAndTimeoutLikeALocalProcess() throws Exception {
        ExecResult failed = sh("echo oops >&2; exit 3", SyncBack.ALL);
        assertThat(failed.exitCode()).isEqualTo(3);
        assertThat(failed.stderr()).contains("oops");
        assertThat(failed.timedOut()).isFalse();

        ExecResult timedOut = client.exec(workspace, List.of("sleep", "60"), Duration.ofSeconds(2), SyncBack.ALL);
        assertThat(timedOut.timedOut()).isTrue();
        assertThat(timedOut.exitCode()).isNull();
        Process leftovers = new ProcessBuilder("docker", "ps", "-aq", "--filter", "name=fh-build-").start();
        assertThat(new String(leftovers.getInputStream().readAllBytes()).trim()).as("containers left behind").isEmpty();
    }

    @Test
    void aHostileBuildSeesNoPlatformSecretsAndCannotTouchTheHost() throws Exception {
        ExecResult result = sh("""
                env > env.txt
                id -u > uid.txt
                (touch /etc/pwned 2>&1 || echo rootfs-readonly) > fs.txt
                (ls /var/run/docker.sock 2>&1 || echo no-docker-socket) > sock.txt
                ls /work/.. > parent.txt
                """, SyncBack.ALL);

        assertThat(result.exitCode()).isZero();
        String env = Files.readString(workspace.resolve("env.txt"));
        assertThat(env).doesNotContain("S3_ARTIFACT_SECRET_KEY").doesNotContain("DB_PASSWORD").doesNotContain("must-not-leak").doesNotContain(TOKEN);
        assertThat(Files.readString(workspace.resolve("uid.txt")).trim()).isNotEqualTo("0");
        assertThat(Files.readString(workspace.resolve("fs.txt"))).contains("rootfs-readonly");
        assertThat(Files.readString(workspace.resolve("sock.txt"))).contains("no-docker-socket");
        assertThat(Files.readString(workspace.resolve("parent.txt"))).doesNotContain("jobs").doesNotContain(temp.getFileName().toString());
    }

    @Test
    void aPoisonedWorkspaceComingBackIsRejectedAndTheLocalOneIsLeftUntouched() throws Exception {
        Files.writeString(workspace.resolve("keep.txt"), "original");

        assertThatThrownBy(() -> sh("ln -s /etc/passwd evil && echo changed > keep.txt", SyncBack.ALL))
                .isInstanceOf(SandboxManagerException.class)
                .hasMessageContaining("rejected");

        assertThat(Files.readString(workspace.resolve("keep.txt"))).isEqualTo("original");
        assertThat(workspace.resolve("evil")).doesNotExist();
    }

    @Test
    void requiresTheTokenButAllowsTheHealthCheck() throws Exception {
        SandboxManagerClient wrong = new SandboxManagerClient(URI.create("http://127.0.0.1:" + server.port()), "not-the-token");
        assertThatThrownBy(() -> wrong.exec(workspace, List.of("true"), Duration.ofSeconds(5), SyncBack.ALL))
                .isInstanceOf(SandboxManagerException.class).hasMessageContaining("401");

        var health = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/healthz")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
    }

    @Test
    void anUnreachableManagerFailsClosedInsteadOfRunningAnything() {
        SandboxManagerClient nobody = new SandboxManagerClient(URI.create("http://127.0.0.1:1"), TOKEN);
        assertThatThrownBy(() -> nobody.exec(workspace, List.of("true"), Duration.ofSeconds(5), SyncBack.ALL))
                .isInstanceOf(SandboxManagerException.class).hasMessageContaining("unreachable");
    }

    @Test
    void buildsRunAsTheConfiguredUnprivilegedUserEvenWhenTheManagerItselfIsDifferent() throws Exception {
        server.close();
        Map<String, String> environment = Map.of("PATH", System.getenv("PATH"), "HOME", System.getenv().getOrDefault("HOME", "/tmp"), "BUILD_USER", "10001:10001");
        server = new ManagerServer(new ManagerConfig("127.0.0.1", 0, TOKEN, Files.createDirectories(temp.resolve("jobs2")), LAUNCHER,
                4, 2, Duration.ofMinutes(5), 64L * 1024 * 1024, 100_000, 60, environment));
        server.start();
        client = new SandboxManagerClient(URI.create("http://127.0.0.1:" + server.port()), TOKEN);
        Files.writeString(workspace.resolve("in.txt"), "uploaded by the manager's own user");

        ExecResult result = sh("id -u > uid.txt && echo more >> in.txt", SyncBack.ALL);

        assertThat(result.exitCode()).isZero();
        assertThat(Files.readString(workspace.resolve("uid.txt")).trim()).isEqualTo("10001");
        assertThat(Files.readString(workspace.resolve("in.txt"))).contains("uploaded").contains("more");
    }

    @Test
    void refusesToRunABuildAsRoot() throws Exception {
        server.close();
        Map<String, String> environment = Map.of("PATH", System.getenv("PATH"), "HOME", System.getenv().getOrDefault("HOME", "/tmp"), "BUILD_USER", "0:0");
        server = new ManagerServer(new ManagerConfig("127.0.0.1", 0, TOKEN, Files.createDirectories(temp.resolve("jobs3")), LAUNCHER,
                4, 2, Duration.ofMinutes(5), 64L * 1024 * 1024, 100_000, 60, environment));
        server.start();
        client = new SandboxManagerClient(URI.create("http://127.0.0.1:" + server.port()), TOKEN);

        ExecResult result = sh("id -u", SyncBack.ALL);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.stderr()).contains("refusing to run a build as root");
    }
}
