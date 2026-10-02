package com.funchole.backend.controlplane.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates only named guided operations. Uncertain reservations never expire or run again. */
@Service
public class McpClientOperationService {
    public enum Status { COMPLETED, INVALID_ID, CHANGED_REQUEST, IN_PROGRESS }
    public record Outcome(Status status, String receiptJson) { }
    private final McpClientOperationRepository repository;
    private final ObjectMapper json = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final TransactionTemplate transaction;

    public McpClientOperationService(McpClientOperationRepository repository, PlatformTransactionManager manager) {
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Outcome execute(UUID tenant, String tool, String operationId,
            Map<String, Object> arguments, Supplier<String> action) {
        if (operationId == null || !operationId.matches("[A-Za-z0-9._:-]{1,128}")) {
            return new Outcome(Status.INVALID_ID, null);
        }
        String hash = fingerprint(arguments);
        boolean reserved = Boolean.TRUE.equals(transaction.execute(status -> repository.reserve(tenant, tool, operationId, hash)));
        if (!reserved) {
            var stored = transaction.execute(status -> repository.get(tenant, tool, operationId));
            if (!hash.equals(stored.requestSha256())) {
                return new Outcome(Status.CHANGED_REQUEST, null);
            }
            if (!"COMPLETED".equals(stored.state())) return new Outcome(Status.IN_PROGRESS, null);
            return new Outcome(Status.COMPLETED, stored.receiptJson());
        }

        // Any exception, process exit, or failed receipt write leaves IN_PROGRESS for inspection.
        String receipt = action.get();
        try {
            transaction.executeWithoutResult(status -> repository.complete(tenant, tool, operationId, hash, receipt));
            return new Outcome(Status.COMPLETED, receipt);
        } catch (Exception exception) {
            return new Outcome(Status.IN_PROGRESS, null);
        }
    }

    private String fingerprint(Map<String, Object> arguments) {
        try {
            byte[] encoded = json.writeValueAsBytes(arguments);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot fingerprint MCP request", exception);
        }
    }

}
