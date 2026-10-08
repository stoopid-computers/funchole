package com.funchole.backend.controlplane.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.funchole.backend.core.base.exception.ApiErrorResponse;
import com.funchole.backend.core.base.exception.ForbiddenException;
import com.funchole.backend.core.base.exception.QuotaExceededException;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import com.funchole.backend.core.base.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;

/**
 * Pins the error contract the workspace UI relies on: every error carries a
 * stable {@code code}, and caller mistakes are 4xx rather than 500.
 */
class ErrorContractTests {

    private final GlobalExceptionHandler core = new GlobalExceptionHandler();
    private final AuthExceptionHandler auth = new AuthExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/things");

    private void assertError(ResponseEntity<ApiErrorResponse> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(code);
        assertThat(response.getBody().path()).isEqualTo("/api/v1/things");
    }

    @Test
    void notFoundQuotaAndForbiddenHaveDistinctCodes() {
        assertError(core.handleNotFound(new ResourceNotFoundException("Flow not found"), request), HttpStatus.NOT_FOUND, "NOT_FOUND");
        assertError(core.handleQuotaExceeded(new QuotaExceededException("limit"), request), HttpStatus.FORBIDDEN, "QUOTA_EXCEEDED");
        assertError(core.handleForbidden(new ForbiddenException("nope"), request), HttpStatus.FORBIDDEN, "FORBIDDEN");
        assertError(core.handleForbidden(new ForbiddenException("admin only", "ADMIN_ONLY"), request), HttpStatus.FORBIDDEN, "ADMIN_ONLY");
    }

    @Test
    void callerMistakesAreNotServerErrors() throws Exception {
        assertError(core.handleBadParameter(new MissingServletRequestParameterException("page", "int"), request), HttpStatus.BAD_REQUEST, "BAD_PARAMETER");
        assertError(core.handleIllegalArgument(new IllegalArgumentException("bad"), request), HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REQUEST");
        assertError(core.handleIllegalState(new IllegalStateException("clash"), request), HttpStatus.CONFLICT, "CONFLICT");
        assertError(core.handleWrongMethod(new HttpRequestMethodNotSupportedException("PATCH"), request), HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED");
        assertError(core.handleUnknownRoute(new NoResourceFoundException(HttpMethod.GET, "/api/v1/things", "things"), request), HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    @Test
    void unexpectedFailuresStayInternal() {
        assertError(core.handleGeneric(new RuntimeException("boom"), request), HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");
    }

    @Test
    void wrongPasswordIs401WithAHumanMessage() {
        ResponseEntity<ApiErrorResponse> response = auth.handleAuthentication(new BadCredentialsException("Bad credentials"), request);

        assertError(response, HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        assertThat(response.getBody().message()).isEqualTo("Incorrect username or password.");
    }

    @Test
    void ourOwnAuthMessagesPassThrough() {
        ResponseEntity<ApiErrorResponse> response = auth.handleAuthentication(new BadCredentialsException("Invalid Google token"), request);

        assertThat(response.getBody().message()).isEqualTo("Invalid Google token");
    }
}
