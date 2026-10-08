package com.funchole.backend.controlplane.activity;

import static org.assertj.core.api.Assertions.assertThat;

import com.funchole.backend.controlplane.dto.ActivitySummaryResponse;
import com.funchole.backend.controlplane.dto.InvocationActivityResponse;
import com.funchole.backend.core.base.response.PaginationResponse;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.funchole.backend.invocation.InvocationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Against a real Postgres, because the owner/source trigger from migration
 * V31 and the JSONB status extraction are the point.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class InvocationActivityTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("funchole")
            .withUsername("test")
            .withPassword("test");

    // The registry wires the NATS publisher eagerly; mock it so the context starts without a NATS server.
    @MockitoBean
    InvocationEventPublisher publisher;

    private static final String GATEWAY_INPUT =
            "{\"method\":\"GET\",\"hostname\":\"a.funchole.dev\",\"path\":\"/\",\"rawUri\":\"/\",\"headers\":{\"Authorization\":\"Bearer x\"}}";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Autowired
    private InvocationActivityService service;

    private final UUID maya = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private UUID mayaFlow;
    private UUID otherFlow;

    @BeforeEach
    void flows() {
        mayaFlow = insertFlow(maya, "flw_booking");
        otherFlow = insertFlow(other, "flw_other");
    }

    private UUID insertFlow(UUID owner, String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO flows (id, app_user_id, gateway_id, flow_key, name, http_method, path) VALUES (?, ?, ?, ?, ?, 'GET', '/')",
                id, owner, UUID.randomUUID(), key, key.replace("flw_", "").toUpperCase());
        return id;
    }

    private UUID insertInvocation(UUID flowId, String flowKey, String status, String input, String result, String ago) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO invocations (id, flow_id, flow_key, flow_version_id, status, input_payload, result, kind, created_at, updated_at, completed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, 'FLOW', now() - ?::interval, now(), now() - ?::interval + interval '2 seconds')",
                id, flowId, flowKey, UUID.randomUUID(), status, input, result, ago, ago);
        return id;
    }

    @Test
    void triggerFillsOwnerAndTellsVisitorTrafficFromTestRuns() {
        UUID visitor = insertInvocation(mayaFlow, "flw_booking", "COMPLETED", GATEWAY_INPUT, "{\"status\":200}", "1 minute");
        UUID test = insertInvocation(mayaFlow, "flw_booking", "COMPLETED", "{}", "{\"status\":200}", "1 minute");

        assertThat(jdbc.queryForObject("SELECT app_user_id FROM invocations WHERE id = ?", UUID.class, visitor)).isEqualTo(maya);
        assertThat(jdbc.queryForObject("SELECT source FROM invocations WHERE id = ?", String.class, visitor)).isEqualTo("GATEWAY");
        assertThat(jdbc.queryForObject("SELECT source FROM invocations WHERE id = ?", String.class, test)).isEqualTo("TEST");
    }

    @Test
    void listIsScopedToTheCallerNewestFirstAndCanFilterBySource() {
        insertInvocation(mayaFlow, "flw_booking", "COMPLETED", GATEWAY_INPUT, "{\"status\":200}", "10 minutes");
        UUID newest = insertInvocation(mayaFlow, "flw_booking", "COMPLETED", GATEWAY_INPUT, "{\"status\":404}", "1 minute");
        insertInvocation(mayaFlow, "flw_booking", "COMPLETED", "{}", "{\"status\":200}", "5 minutes");
        insertInvocation(otherFlow, "flw_other", "COMPLETED", GATEWAY_INPUT, "{\"status\":200}", "1 minute");

        PaginationResponse<InvocationActivityResponse> all = service.list(maya, null, null, 1, 20);
        PaginationResponse<InvocationActivityResponse> visitors = service.list(maya, "gateway", null, 1, 20);

        assertThat(all.totalElements()).isEqualTo(3);
        assertThat(visitors.totalElements()).isEqualTo(2);
        assertThat(visitors.items().get(0).invocationId()).isEqualTo(newest);
        assertThat(visitors.items().get(0).httpStatus()).isEqualTo(404);
        assertThat(visitors.items().get(0).flowName()).isEqualTo("BOOKING");
        assertThat(visitors.items().get(0).durationMs()).isEqualTo(2000L);
        assertThat(service.list(other, null, null, 1, 20).totalElements()).isEqualTo(1);
    }

    @Test
    void summaryCountsOnlyVisitorTrafficFromTheLastDay() {
        insertInvocation(mayaFlow, "flw_booking", "COMPLETED", GATEWAY_INPUT, "{\"status\":200}", "1 hour");
        insertInvocation(mayaFlow, "flw_booking", "FAILED", GATEWAY_INPUT, null, "2 hours");
        insertInvocation(mayaFlow, "flw_booking", "COMPLETED", GATEWAY_INPUT, "{\"status\":500}", "3 hours");
        insertInvocation(mayaFlow, "flw_booking", "COMPLETED", GATEWAY_INPUT, "{\"status\":200}", "3 days");
        insertInvocation(mayaFlow, "flw_booking", "COMPLETED", "{}", "{\"status\":200}", "1 hour");

        ActivitySummaryResponse summary = service.summary(maya);

        assertThat(summary.requests24h()).isEqualTo(3);
        assertThat(summary.failed24h()).isEqualTo(2);
        assertThat(summary.lastRequestAt()).isNotNull();
        assertThat(summary.pages()).hasSize(1);
        assertThat(summary.pages().get(0).flowKey()).isEqualTo("flw_booking");
        assertThat(summary.pages().get(0).requests24h()).isEqualTo(3);
        assertThat(service.summary(UUID.randomUUID()).requests24h()).isZero();
    }

    @Test
    void retentionPurgesOldHistoryOnlyWhenEnabled() {
        UUID oldOne = insertInvocation(mayaFlow, "flw_booking", "COMPLETED", GATEWAY_INPUT, "{\"status\":200}", "10 days");
        UUID recent = insertInvocation(mayaFlow, "flw_booking", "COMPLETED", GATEWAY_INPUT, "{\"status\":200}", "1 day");
        jdbc.update("INSERT INTO invocation_step_executions (id, invocation_id, flow_id, flow_version_id, step_id, position, component_type, component_id, component_version_id, status) "
                + "VALUES (?, ?, ?, ?, ?, 1, 'FUNCTION', ?, ?, 'COMPLETED')",
                UUID.randomUUID(), oldOne, mayaFlow, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(new InvocationRetentionJob(namedJdbc, new InvocationRetentionProperties(0)).purge(OffsetDateTime.now())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invocations", Long.class)).isEqualTo(2L);

        assertThat(new InvocationRetentionJob(namedJdbc, new InvocationRetentionProperties(3)).purge(OffsetDateTime.now())).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM invocations", UUID.class)).containsExactly(recent);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invocation_step_executions", Long.class)).isZero();
    }
}
