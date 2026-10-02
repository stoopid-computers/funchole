package com.funchole.backend.controlplane.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Durable reservations. A reservation commits before any tool side effect begins. */
@Repository
public class McpClientOperationRepository {
    public record Stored(String requestSha256, String state, String receiptJson) { }

    private final JdbcTemplate jdbc;

    public McpClientOperationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean reserve(UUID tenant, String tool, String operationId, String hash) {
        return jdbc.update("""
                INSERT INTO mcp_client_operations (tenant_id, tool_key, operation_id, request_sha256, state)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS') ON CONFLICT DO NOTHING
                """, tenant, tool, operationId, hash) == 1;
    }

    public Stored get(UUID tenant, String tool, String operationId) {
        return jdbc.queryForObject("""
                SELECT request_sha256, state, receipt_json FROM mcp_client_operations
                WHERE tenant_id = ? AND tool_key = ? AND operation_id = ?
                """, (rs, row) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3)),
                tenant, tool, operationId);
    }

    public void complete(UUID tenant, String tool, String operationId, String hash, String receiptJson) {
        int count = jdbc.update("""
                UPDATE mcp_client_operations SET state = 'COMPLETED', receipt_json = ?, completed_at = now()
                WHERE tenant_id = ? AND tool_key = ? AND operation_id = ?
                    AND request_sha256 = ? AND state = 'IN_PROGRESS'
                """, receiptJson, tenant, tool, operationId, hash);
        if (count != 1) throw new IllegalStateException("MCP operation reservation was lost");
    }
}
