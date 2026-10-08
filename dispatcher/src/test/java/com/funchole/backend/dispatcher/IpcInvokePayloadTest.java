package com.funchole.backend.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IpcInvokePayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private RuntimeExecutionRequest request(UUID tenantId) {
        return new RuntimeExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, "FUNCTION", UUID.randomUUID(), UUID.randomUUID(), "NODE", "{}", Map.of(), java.util.List.of(), tenantId);
    }

    @Test
    void carriesTheTenantWhenKnown() throws Exception {
        UUID tenant = UUID.randomUUID();
        JsonNode json = objectMapper.valueToTree(IpcInvokePayload.from(request(tenant)));
        assertThat(json.get("tenantId").asText()).isEqualTo(tenant.toString());
    }

    @Test
    void omitsTheFieldEntirelyWhenUnknownSoAnOlderRuntimeStillParsesIt() throws Exception {
        JsonNode json = objectMapper.valueToTree(IpcInvokePayload.from(request(null)));
        assertThat(json.has("tenantId")).isFalse();
    }

    @Test
    void earlierConstructorsStillWork() {
        RuntimeExecutionRequest old = new RuntimeExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), 1, "FUNCTION", UUID.randomUUID(), UUID.randomUUID(), "NODE", "{}");
        assertThat(old.tenantId()).isNull();
    }
}
