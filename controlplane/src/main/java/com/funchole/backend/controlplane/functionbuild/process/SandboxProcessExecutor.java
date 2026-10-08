package com.funchole.backend.controlplane.functionbuild.process;

import com.funchole.backend.sandbox.protocol.ExecResult;
import com.funchole.backend.sandbox.protocol.SandboxManagerClient;
import com.funchole.backend.sandbox.protocol.SandboxManagerException;
import com.funchole.backend.sandbox.protocol.SyncBack;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs build commands in the sandbox manager instead of on this host, so tenant dependencies and build
 * scripts never execute next to the controlplane's credentials. If the manager cannot be reached the
 * build fails; there is deliberately no fallback to running locally.
 */
public final class SandboxProcessExecutor implements ProcessExecutor {

    private final SandboxManagerClient client;

    public SandboxProcessExecutor(SandboxManagerClient client) {
        this.client = client;
    }

    @Override
    public ProcessResult execute(List<String> command, Path workingDirectory, Duration timeout) {
        return execute(command, workingDirectory, timeout, WorkspaceSync.ALL);
    }

    @Override
    public ProcessResult execute(List<String> command, Path workingDirectory, Duration timeout, WorkspaceSync sync) {
        try {
            ExecResult result = client.exec(workingDirectory, command, timeout, switch (sync) {
                case NONE -> SyncBack.NONE;
                case ALL -> SyncBack.ALL;
                case ALL_BUT_DEPENDENCIES -> SyncBack.EXCLUDING_NODE_MODULES;
            });
            return new ProcessResult(result.exitCode(), result.stdout(), result.stderr(), result.timedOut());
        } catch (SandboxManagerException exception) {
            throw new IllegalStateException("The isolated build environment failed: " + exception.getMessage(), exception);
        }
    }
}
