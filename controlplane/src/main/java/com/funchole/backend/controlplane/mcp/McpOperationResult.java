package com.funchole.backend.controlplane.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;

/** Safe, shared receipt for all eleven operations and both protocol paths. */
public record McpOperationResult(boolean ok, String code, String message, @jakarta.annotation.Nullable String reference,
                                 @jakarta.annotation.Nullable Object data, Map<String, String> links, List<String> warnings) {
    private static final Logger LOG = LoggerFactory.getLogger(McpOperationResult.class);
    public static final List<String> ERROR_CODES = List.of("INVALID_INPUT", "NOT_FOUND", "NOT_AUTHORIZED",
            "CONFLICT", "PARTIAL_FAILURE", "OPERATION_FAILED");

    public static McpOperationResult success(String ref, Object data) {
        return new McpOperationResult(true, "OK", "", ref, data,
                ref == null ? Map.of() : Map.of("state", ref), List.of());
    }

    public static McpOperationResult failure(String code, String message, String ref) {
        if (!ERROR_CODES.contains(code)) throw new IllegalArgumentException("Unknown safe error code");
        return new McpOperationResult(false, code, message, ref, null,
                ref == null ? Map.of("guide", "funchole://guides/troubleshooting")
                        : Map.of("state", ref, "guide", "funchole://guides/troubleshooting"), List.of());
    }

    public static McpOperationResult run(Callable<McpOperationResult> operation) {
        try { return operation.call(); }
        catch (ResourceNotFoundException exception) {
            return failure("NOT_FOUND", "Resource unavailable. Discover an owned target and read its state.", null);
        } catch (AccessDeniedException exception) {
            return failure("NOT_AUTHORIZED", "This operation is not authorized for the current account.", null);
        } catch (IllegalArgumentException exception) {
            return failure("INVALID_INPUT", "Check the exact tool contract and supplied references before retrying.", null);
        } catch (Exception exception) {
            LOG.warn("MCP operation failed: {}", exception.getClass().getName());
            return failure("OPERATION_FAILED", "Read current state before retrying. Ask the operator to inspect logs if needed.", null);
        }
    }
}
