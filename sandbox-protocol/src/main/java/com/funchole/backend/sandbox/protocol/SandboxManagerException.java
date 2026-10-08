package com.funchole.backend.sandbox.protocol;

/** The manager could not be reached or refused the request. Callers must fail the build, never fall back to running code locally. */
public class SandboxManagerException extends RuntimeException {

    public SandboxManagerException(String message) {
        super(message);
    }

    public SandboxManagerException(String message, Throwable cause) {
        super(message, cause);
    }
}
