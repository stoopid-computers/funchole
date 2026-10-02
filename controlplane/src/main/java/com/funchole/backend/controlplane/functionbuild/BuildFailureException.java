package com.funchole.backend.controlplane.functionbuild;

import java.util.List;
import java.util.UUID;

/**
 * Structured, transport-neutral diagnostics shared by every {@link RuntimeBuilder}'s
 * build-stage failure (Node's dependency install, a static site's install+build,
 * and any future runtime's own stages) - one shared shape so
 * {@code BuildExceptionHandler} renders all of them the same way without a
 * per-runtime handler method.
 */
public abstract class BuildFailureException extends RuntimeException {

    private final UUID functionVersionId;
    private final String stage;
    private final List<String> command;
    private final Integer exitCode;
    private final String stdout;
    private final String stderr;
    private final boolean timedOut;

    protected BuildFailureException(
            String message,
            UUID functionVersionId,
            String stage,
            List<String> command,
            Integer exitCode,
            String stdout,
            String stderr,
            boolean timedOut
    ) {
        super(message);
        this.functionVersionId = functionVersionId;
        this.stage = stage;
        this.command = List.copyOf(command);
        this.exitCode = exitCode;
        this.stdout = stdout;
        this.stderr = stderr;
        this.timedOut = timedOut;
    }

    public UUID functionVersionId() {
        return functionVersionId;
    }

    public String stage() {
        return stage;
    }

    public List<String> command() {
        return command;
    }

    public Integer exitCode() {
        return exitCode;
    }

    public String stdout() {
        return stdout;
    }

    public String stderr() {
        return stderr;
    }

    public boolean timedOut() {
        return timedOut;
    }

    private static final String EXAMPLE_TOOL_HINT =
            " Read funchole://guides/troubleshooting before retrying. Read a funchole://examples/ runtime "
                    + "template or the Function Version's logs view for this stage's stdout/stderr.";

    protected static String buildMessage(String label, UUID functionVersionId, String stage, List<String> command, Integer exitCode, boolean timedOut) {
        String commandText = String.join(" ", command);
        if (timedOut) {
            return label + " stage '" + stage + "' timed out running '" + commandText
                    + "' for function version: " + functionVersionId + EXAMPLE_TOOL_HINT;
        }
        return label + " stage '" + stage + "' failed (exit code " + exitCode + ") running '" + commandText
                + "' for function version: " + functionVersionId + EXAMPLE_TOOL_HINT;
    }
}
