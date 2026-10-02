CREATE TABLE mcp_client_operations (
    tenant_id UUID NOT NULL,
    tool_key VARCHAR(100) NOT NULL,
    operation_id VARCHAR(128) NOT NULL,
    request_sha256 CHAR(64) NOT NULL,
    state VARCHAR(20) NOT NULL CHECK (state IN ('IN_PROGRESS', 'COMPLETED')),
    receipt_json TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, tool_key, operation_id),
    CHECK ((state = 'IN_PROGRESS' AND receipt_json IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND receipt_json IS NOT NULL AND completed_at IS NOT NULL))
);
