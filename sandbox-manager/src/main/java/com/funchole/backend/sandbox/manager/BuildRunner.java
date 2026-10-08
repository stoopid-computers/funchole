package com.funchole.backend.sandbox.manager;

import com.funchole.backend.sandbox.protocol.ExecRequest;
import com.funchole.backend.sandbox.protocol.ExecResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs one command in a locked-down container (see docker/sandbox/build-launch.sh). Nothing from this
 * process's environment is passed in, the command's time is enforced here (the container is removed on
 * expiry), and output is size-capped so a chatty or hostile build cannot exhaust memory.
 */
class BuildRunner {
    private static final Logger logger = LoggerFactory.getLogger(BuildRunner.class);
    private static final int MAX_ARGS = 64;
    private static final int MAX_ARG_LENGTH = 4096;

    private final ManagerConfig config;
    private final Semaphore concurrentRuns;

    BuildRunner(ManagerConfig config) {
        this.config = config;
        this.concurrentRuns = new Semaphore(config.maxConcurrentRuns());
    }

    ExecResult run(JobStore.Job job, ExecRequest request) throws InterruptedException {
        validate(request);
        int timeoutSeconds = Math.min(Math.max(1, request.timeoutSeconds()), config.maxTimeoutSeconds());
        if (!concurrentRuns.tryAcquire(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("all build slots are busy");
        }
        prepareWorkspace(job);
        String name = "fh-build-" + job.id.substring(0, 8) + "-" + UUID.randomUUID().toString().substring(0, 6);
        try {
            List<String> command = new ArrayList<>(List.of(config.buildLauncher().toString(), name, job.workspace.toString(), "--"));
            command.addAll(request.command());
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().clear();
            builder.environment().putAll(config.launcherEnvironment());
            builder.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            Process process = builder.start();

            Capture stdout = new Capture(process.getInputStream(), config.maxOutputChars());
            Capture stderr = new Capture(process.getErrorStream(), config.maxOutputChars());
            stdout.start();
            stderr.start();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                logger.warn("Build timed out after {}s, removing container {}", timeoutSeconds, name);
                removeContainer(name);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                return new ExecResult(null, stdout.text(), stderr.text(), true);
            }
            return new ExecResult(process.exitValue(), stdout.text(), stderr.text(), false);
        } catch (IOException exception) {
            throw new IllegalStateException("could not start the build launcher: " + exception.getMessage(), exception);
        } finally {
            concurrentRuns.release();
        }
    }

    /**
     * When builds run as a fixed unprivileged user (BUILD_USER) that differs from this process's own
     * (typically root, to reach the Docker socket), the uploaded files must be writable by that user.
     * Done once per job: files the build creates afterwards already belong to it.
     */
    private void prepareWorkspace(JobStore.Job job) {
        if (job.prepared || !config.launcherEnvironment().containsKey("BUILD_USER")) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(job.workspace)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    continue;
                }
                var permissions = new java.util.HashSet<>(java.nio.file.Files.getPosixFilePermissions(path));
                permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_READ);
                permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
                if (java.nio.file.Files.isDirectory(path) || permissions.contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE)) {
                    permissions.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
                }
                java.nio.file.Files.setPosixFilePermissions(path, permissions);
            }
            job.prepared = true;
        } catch (IOException exception) {
            throw new IllegalStateException("could not prepare the workspace: " + exception.getMessage(), exception);
        }
    }

    private static void validate(ExecRequest request) {
        List<String> command = request.command();
        if (command == null || command.isEmpty() || command.size() > MAX_ARGS) {
            throw new IllegalArgumentException("command must have between 1 and " + MAX_ARGS + " arguments");
        }
        for (String argument : command) {
            if (argument == null || argument.length() > MAX_ARG_LENGTH || argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("invalid command argument");
            }
        }
    }

    private void removeContainer(String name) {
        try {
            ProcessBuilder remove = new ProcessBuilder("docker", "rm", "-f", name);
            remove.environment().clear();
            remove.environment().putAll(config.launcherEnvironment());
            Process process = remove.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException exception) {
            logger.warn("Could not remove container {}: {}", name, exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Reads a stream fully but keeps only the first {@code limit} characters. */
    private static final class Capture extends Thread {
        private final InputStream stream;
        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;

        Capture(InputStream stream, int limit) {
            super("build-output");
            setDaemon(true);
            this.stream = stream;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    synchronized (text) {
                        if (text.length() < limit) {
                            text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                        } else {
                            truncated = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // The process ended or was killed.
            }
        }

        String text() throws InterruptedException {
            join(5000);
            synchronized (text) {
                String value = text.length() > limit ? text.substring(0, limit) : text.toString();
                return truncated || text.length() > limit ? value + "\n[output truncated]" : value;
            }
        }
    }
}
