package com.funchole.backend.controlplane.controller;

import com.funchole.backend.core.base.exception.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Sign-in failures are the caller's mistake (401 with a clear message), not
 * a server fault. Lives in {@code controlplane} because Spring Security
 * does; ordered first so the generic catch-all in {@code core} never wins.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuthExceptionHandler {

    static final String WRONG_CREDENTIALS = "Incorrect username or password.";

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthentication(AuthenticationException exception, HttpServletRequest request) {
        // Spring's own text ("Bad credentials") is not for people; our own
        // messages (for example about a Google token) already are.
        String message = "Bad credentials".equals(exception.getMessage()) || exception.getMessage() == null
                ? WRONG_CREDENTIALS
                : exception.getMessage();
        ApiErrorResponse body = new ApiErrorResponse(
                OffsetDateTime.now(),
                HttpStatus.UNAUTHORIZED.value(),
                HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                message,
                request.getRequestURI(),
                List.of(),
                "INVALID_CREDENTIALS"
        );
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }
}
