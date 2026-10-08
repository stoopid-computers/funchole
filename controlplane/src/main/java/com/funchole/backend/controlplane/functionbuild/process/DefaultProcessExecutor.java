package com.funchole.backend.controlplane.functionbuild.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Real {@link ProcessExecutor}: wraps {@link ProcessBuilder}. Registered as a bean by
 * {@code BuildIsolationConfig}, which decides between this and the sandbox executor. Stdout/stderr
 * are drained on separate threads concurrently with the process running, to
 * avoid the classic deadlock where a process blocks writing to a full pipe
 * while nothing is reading the other one.
 *
 * <p>Both a timeout and an interrupted calling thread forcibly kill the
 * child and block until the OS has actually reaped it before this class
 * considers execution complete - {@link #execute} never returns (or
 * propagates an exception) while the child is still running.
 */
public class DefaultProcessExecutor implements ProcessExecutor {

    private static final Duration STREAM_DRAIN_GRACE_PERIOD = Duration.ofSeconds(5);

    @Override
    public ProcessResult execute(List<String> command, Path workingDirectory, Duration timeout) {
        ProcessBuilder processBuilder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        ExecutorService streamReaders = Executors.newFixedThreadPool(2);
        Process process = null;
        try {
            process = processBuilder.start();
            Process runningProcess = process;
            Future<String> stdout = streamReaders.submit(() -> readFully(runningProcess.getInputStream()));
            Future<String> stderr = streamReaders.submit(() -> readFully(runningProcess.getErrorStream()));

            boolean finishedInTime = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finishedInTime) {
                terminateAndAwait(process);
                return new ProcessResult(null, awaitOutput(stdout, "stdout"), awaitOutput(stderr, "stderr"), true);
            }
            return new ProcessResult(process.exitValue(), awaitOutput(stdout, "stdout"), awaitOutput(stderr, "stderr"), false);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to execute command: " + command, exception);
        } catch (InterruptedException exception) {
            // The wait for the timeout OR the wait for forced termination was
            // itself interrupted - either way the child must not be left
            // running, so kill it unconditionally before surfacing this.
            if (process != null) {
                terminateProcessTree(process);
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing command: " + command, exception);
        } finally {
            streamReaders.shutdownNow();
        }
    }

    /**
     * Forcibly kills the process and blocks until the OS has actually
     * reaped it, so a timed-out {@link #execute} never returns while the
     * child is still running.
     */
    private void terminateAndAwait(Process process) throws InterruptedException {
        terminateProcessTree(process);
        process.waitFor();
    }

    private void terminateProcessTree(Process process) {
        process.toHandle()
                .descendants()
                .sorted(Comparator.comparingLong(ProcessHandle::pid).reversed())
                .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private String readFully(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Best-effort: capture failures never fail {@link #execute} itself, but
     * are reported as a distinct marker rather than silently collapsed into
     * an empty string indistinguishable from genuinely empty output.
     */
    private String awaitOutput(Future<String> future, String streamName) {
        try {
            return future.get(STREAM_DRAIN_GRACE_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            return "<" + streamName + " capture failed: " + exception.getCause() + ">";
        } catch (TimeoutException exception) {
            return "<" + streamName + " capture timed out>";
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "<" + streamName + " capture interrupted>";
        }
    }
}
