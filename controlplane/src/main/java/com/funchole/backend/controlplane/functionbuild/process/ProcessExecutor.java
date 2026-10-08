package com.funchole.backend.controlplane.functionbuild.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Small, testable boundary around running an external build command (e.g.
 * {@code npm ci}, or a future Python/Go/Rust equivalent) so no
 * {@code RuntimeBuilder} ever needs to call {@link ProcessBuilder} directly.
 * Generic and runtime-neutral: nothing here knows about Node, npm, or any
 * other specific runtime. Tests substitute a fake implementation instead of
 * spawning real processes or hitting a package registry.
 */
public interface ProcessExecutor {

    /**
     * Runs {@code command} in {@code workingDirectory}, capturing stdout and
     * stderr. If the process does not finish within {@code timeout}, it is
     * forcibly terminated - and confirmed terminated - before this method
     * returns, with the result's {@code timedOut} set.
     */
    ProcessResult execute(List<String> command, Path workingDirectory, Duration timeout);

    /**
     * As {@link #execute(List, Path, Duration)}, telling the executor which of the command's changes the
     * caller will read afterwards. Executors that run in place need nothing special, hence the default.
     */
    default ProcessResult execute(List<String> command, Path workingDirectory, Duration timeout, WorkspaceSync sync) {
        return execute(command, workingDirectory, timeout);
    }
}
