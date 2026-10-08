package com.funchole.backend.sandbox.protocol;

/** Same shape as the controlplane's ProcessResult: {@code exitCode} is null exactly when {@code timedOut}. */
public record ExecResult(Integer exitCode, String stdout, String stderr, boolean timedOut) {
}
