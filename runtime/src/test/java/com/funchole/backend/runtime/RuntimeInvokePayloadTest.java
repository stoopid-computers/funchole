package com.funchole.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuntimeInvokePayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String json(String extra) {
        return "{\"invocationId\":\"" + UUID.randomUUID() + "\",\"attempt\":1,\"componentType\":\"FUNCTION\","
                + "\"componentVersionId\":\"" + UUID.randomUUID() + "\",\"runtimeType\":\"NODE\",\"input\":\"{}\"" + extra + "}";
    }

    @Test
    void readsTheTenantWhenPresent() throws Exception {
        UUID tenant = UUID.randomUUID();
        RuntimeInvokePayload payload = objectMapper.readValue(json(",\"tenantId\":\"" + tenant + "\""), RuntimeInvokePayload.class);
        assertThat(payload.tenantId()).isEqualTo(tenant);
    }

    @Test
    void parsesAnOlderDispatcherMessageWithoutATenant() throws Exception {
        assertThat(objectMapper.readValue(json(""), RuntimeInvokePayload.class).tenantId()).isNull();
    }

    @Test
    void ignoresFieldsAddedByANewerDispatcher() throws Exception {
        assertThat(objectMapper.readValue(json(",\"someFutureField\":42"), RuntimeInvokePayload.class).input()).isEqualTo("{}");
    }
}
