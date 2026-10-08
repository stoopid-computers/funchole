package com.funchole.backend.runtime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code docker/sandbox/launch.sh}, the single place that defines how a sandbox is locked
 * down. Only {@code SANDBOX_*} settings and {@code PATH}/{@code HOME} reach the script, so none of
 * this process's own credentials (S3 keys, database password, ...) can leak into a sandbox.
 */
final class DockerSandboxLauncher implements SandboxLauncher {
    private static final Logger logger = LoggerFactory.getLogger(DockerSandboxLauncher.class);

    private final Path launcher;
    private final Map<String, String> sandboxEnvironment;

    DockerSandboxLauncher(Path launcher, Map<String, String> environment) {
        this.launcher = launcher;
        this.sandboxEnvironment = environment.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("SANDBOX_") || entry.getKey().equals("PATH") || entry.getKey().equals("HOME"))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public Process launch(String sandboxName) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(launcher.toString(), sandboxName);
        builder.environment().clear();
        builder.environment().putAll(sandboxEnvironment);
        return builder.start();
    }

    @Override
    public void destroy(String sandboxName) {
        try {
            Process remove = new ProcessBuilder("docker", "rm", "-f", sandboxName)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!remove.waitFor(10, TimeUnit.SECONDS)) {
                remove.destroyForcibly();
            }
        } catch (IOException exception) {
            logger.warn("Could not remove sandbox {}: {}", sandboxName, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
