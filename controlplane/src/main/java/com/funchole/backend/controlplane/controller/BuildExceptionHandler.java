package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.functionbuild.BuildFailureException;
import com.funchole.backend.core.base.exception.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Lives in {@code controlplane}, not {@code core}, because
 * {@link BuildFailureException} does - {@code core} cannot depend on
 * {@code controlplane}. Spring resolves {@code @ExceptionHandler} methods
 * across every {@code @RestControllerAdvice} bean by exception-type
 * specificity, so this composes correctly alongside the generic
 * {@code core.GlobalExceptionHandler} without either needing to know about
 * the other. One handler for every {@code RuntimeBuilder}'s build failures -
 * Node's, a static site's, and any future runtime's - since they all share
 * the same diagnostic shape.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BuildExceptionHandler {

    @ExceptionHandler(BuildFailureException.class)
    public ResponseEntity<ApiErrorResponse> handleBuildFailure(BuildFailureException exception, HttpServletRequest request) {
        List<String> details = new ArrayList<>();
        details.add("stage: " + exception.stage());
        details.add("command: " + String.join(" ", exception.command()));
        details.add("timedOut: " + exception.timedOut());
        if (exception.exitCode() != null) {
            details.add("exitCode: " + exception.exitCode());
        }
        if (exception.stdout() != null && !exception.stdout().isBlank()) {
            details.add("stdout: " + exception.stdout());
        }
        if (exception.stderr() != null && !exception.stderr().isBlank()) {
            details.add("stderr: " + exception.stderr());
        }

        ApiErrorResponse body = new ApiErrorResponse(
                OffsetDateTime.now(),
                HttpStatus.UNPROCESSABLE_CONTENT.value(),
                HttpStatus.UNPROCESSABLE_CONTENT.getReasonPhrase(),
                exception.getMessage(),
                request.getRequestURI(),
                details,
                "BUILD_FAILED"
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(body);
    }
}
