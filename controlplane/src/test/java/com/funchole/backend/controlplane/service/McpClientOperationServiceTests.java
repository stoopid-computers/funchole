package com.funchole.backend.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/** Exercises committed reservations against the application's real PostgreSQL table. */
@SpringBootTest
@ActiveProfiles("test")
class McpClientOperationServiceTests {
    @Autowired McpClientOperationService operations;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void completedReceiptSurvivesServiceRestartAndScopesTenantAndTool() {
        UUID tenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        String receipt = "{\"ok\":false,\"code\":\"PARTIAL_FAILURE\"}";
        var first = operations.execute(tenant, "build_function", "attempt-1", Map.of("secret", "never-persist-me"), () -> {
            calls.incrementAndGet();
            return receipt;
        });
        assertThat(first).isEqualTo(new McpClientOperationService.Outcome(McpClientOperationService.Status.COMPLETED, receipt));
        var restarted = new McpClientOperationService(new McpClientOperationRepository(jdbc), transactions);
        assertThat(restarted.execute(tenant, "build_function", "attempt-1", Map.of("secret", "never-persist-me"),
                () -> { calls.incrementAndGet(); return "duplicate"; }).receiptJson()).isEqualTo(receipt);
        assertThat(calls).hasValue(1);
        assertThat(restarted.execute(tenant, "publish_flow", "attempt-1", Map.of("secret", "never-persist-me"),
                () -> "tool receipt").receiptJson()).isEqualTo("tool receipt");
        assertThat(restarted.execute(otherTenant, "build_function", "attempt-1", Map.of("secret", "never-persist-me"),
                () -> "tenant receipt").receiptJson()).isEqualTo("tenant receipt");
        assertThat(jdbc.queryForObject("SELECT request_sha256 FROM mcp_client_operations WHERE tenant_id = ? AND tool_key = ?",
                String.class, tenant, "build_function")).hasSize(64).doesNotContain("never-persist-me");
    }

    @Test
    void changedRequestConflictsWithoutRunningAndUncertainReservationStaysBlocked() {
        UUID tenant = UUID.randomUUID();
        AtomicInteger calls = new AtomicInteger();
        operations.execute(tenant, "configure", "same-key", Map.of("value", 1), () -> "first");
        var changed = operations.execute(tenant, "configure", "same-key", Map.of("value", 2),
                () -> { calls.incrementAndGet(); return "second"; });
        assertThat(changed.status()).isEqualTo(McpClientOperationService.Status.CHANGED_REQUEST);
        assertThat(calls).hasValue(0);
        assertThatThrownBy(() -> operations.execute(tenant, "retire", "crash", Map.of("ref", "one"), () -> {
            throw new IllegalStateException("simulated process exit after reservation");
        })).isInstanceOf(IllegalStateException.class);
        var uncertain = operations.execute(tenant, "retire", "crash", Map.of("ref", "one"),
                () -> { calls.incrementAndGet(); return "duplicate"; });
        assertThat(uncertain.status()).isEqualTo(McpClientOperationService.Status.IN_PROGRESS);
        assertThat(calls).hasValue(0);
    }

    @Test
    void concurrentDuplicateSeesCommittedReservationAndCannotExecuteAgain() throws Exception {
        UUID tenant = UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> operations.execute(tenant, "invoke", "parallel", Map.of("payload", 1), () -> {
                calls.incrementAndGet();
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timed out"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                return "done";
            }));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var duplicate = operations.execute(tenant, "invoke", "parallel", Map.of("payload", 1),
                    () -> { calls.incrementAndGet(); return "duplicate"; });
            assertThat(duplicate.status()).isEqualTo(McpClientOperationService.Status.IN_PROGRESS);
            assertThat(calls).hasValue(1);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).receiptJson()).isEqualTo("done");
        }
    }
}
