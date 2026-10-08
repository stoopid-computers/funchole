package com.funchole.backend.core.base.exception;

public class ForbiddenException extends RuntimeException {

    /** Default error code for a plain permission failure. */
    public static final String DEFAULT_CODE = "FORBIDDEN";

    private final String code;

    public ForbiddenException(String message) {
        this(message, DEFAULT_CODE);
    }

    public ForbiddenException(String message, String code) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
