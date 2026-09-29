package com.funchole.backend.gateway.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.gateway.GatewayRegistry;
import com.funchole.backend.gateway.GatewayRegistrySnapshot;
import com.funchole.backend.gateway.GatewayRequestContext;
import com.funchole.backend.gateway.GatewayRuntimeEntry;
import com.funchole.backend.gateway.flow.FlowResolution;
import com.funchole.backend.gateway.flow.FlowResolver;
import com.funchole.backend.gateway.flow.RouteMatch;
import com.funchole.backend.invocation.CreateInvocationRequest;
import com.funchole.backend.invocationcontract.DirectInvocationRequest;
import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationKind;
import com.funchole.backend.invocation.InvocationRegistry;
import com.funchole.backend.invocation.InvocationStatus;
import com.funchole.backend.invocation.InvocationTransition;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GatewayHttpHandlerInvocationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final UUID GATEWAY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID FLOW_ID = UUID.fromString("55555555-5555-5555-5555-555555555553");
    private static final UUID FLOW_VERSION_ID = UUID.fromString("66666666-6666-6666-6666-666666666663");
    private static final UUID INVOCATION_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private ScheduledExecutorService timeoutExecutor;
    private ExecutorService invocationExecutor;

    @AfterEach
    void tearDown() {
        if (timeoutExecutor != null) {
            timeoutExecutor.shutdownNow();
        }
        if (invocationExecutor != null) {
            invocationExecutor.shutdownNow();
        }
    }

    private ExecutorService gatewayInvocationExecutor() {
        return newDirectExecutor();
    }

    private static ExecutorService newDirectExecutor() {
        return new java.util.concurrent.AbstractExecutorService() {
            public void execute(Runnable command) { command.run(); }
            public void shutdown() { }
            public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
            public boolean isShutdown() { return true; }
            public boolean isTerminated() { return true; }
            public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) { return true; }
        };
    }

    @Test
    void createsInvocationAndDoesNotWriteAResponseUntilCompletion() throws Exception {
        CountingFlowResolver flowResolver = flowResolver();
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver, invocationRegistry, pendingRegistry);

        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();

        assertNull(channel.readOutbound());
        assertEquals(1, flowResolver.calls);
        assertEquals(FLOW_ID, invocationRegistry.request.flowId());
        assertEquals("flw_checkout", invocationRegistry.request.flowKey());
        assertEquals(FLOW_VERSION_ID, invocationRegistry.request.flowVersionId());
        assertTrue(invocationRegistry.request.inputPayload().contains("\"path\":\"/checkout\""));
        assertTrue(invocationRegistry.request.inputPayload().contains("\"body\":\"{\\\"total\\\":100}\""));
        assertEquals(1, pendingRegistry.pendingCount());
    }

    @Test
    void invocationInputCarriesRequestHeadersAndParsedCookies() throws Exception {
        CountingFlowResolver flowResolver = flowResolver();
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver, invocationRegistry, pendingRegistry);

        DefaultFullHttpRequest request = checkoutRequest();
        request.headers().add("X-Custom-Header", "abc");
        request.headers().add(HttpHeaderNames.COOKIE, "session=xyz; theme=dark");
        request.headers().add(HttpHeaderNames.CONNECTION, "keep-alive");

        channel.writeInbound(request);
        channel.runPendingTasks();

        JsonNode input = OBJECT_MAPPER.readTree(invocationRegistry.request.inputPayload());
        assertEquals("abc", input.at("/headers/X-Custom-Header/0").asText());
        assertEquals("xyz", input.at("/cookies/session").asText());
        assertEquals("dark", input.at("/cookies/theme").asText());
        // Hop-by-hop headers are meaningful only for this one connection and
        // must never reach invocation-level code.
        assertTrue(input.at("/headers/Connection").isMissingNode());
    }

    @Test
    void invocationInputPreservesMultipleValuesForARepeatedHeader() throws Exception {
        CountingFlowResolver flowResolver = flowResolver();
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver, invocationRegistry, pendingRegistry);

        DefaultFullHttpRequest request = checkoutRequest();
        request.headers().add("X-Trace", "first");
        request.headers().add("X-Trace", "second");

        channel.writeInbound(request);
        channel.runPendingTasks();

        JsonNode input = OBJECT_MAPPER.readTree(invocationRegistry.request.inputPayload());
        assertEquals("first", input.at("/headers/X-Trace/0").asText());
        assertEquals("second", input.at("/headers/X-Trace/1").asText());
    }

    @Test
    void writesFinalResponseWhenInvocationCompletes() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();

        invocationRegistry.completeWith("{\"status\":201,\"body\":{\"ok\":true}}");
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.CREATED, response.status());
        JsonNode body = OBJECT_MAPPER.readTree(response.content().toString(StandardCharsets.UTF_8));
        assertTrue(body.at("/ok").asBoolean());
    }

    @Test
    void writesEachSetCookieValueAsItsOwnHeaderLineNotOneCommaJoinedHeader() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();

        invocationRegistry.completeWith(
                "{\"status\":200,\"body\":{\"ok\":true},"
                        + "\"headers\":{\"Set-Cookie\":[\"a=1; Path=/\",\"b=2; Path=/\"]}}");
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        java.util.List<String> setCookies = response.headers().getAll(HttpHeaderNames.SET_COOKIE);
        assertEquals(2, setCookies.size());
        assertTrue(setCookies.contains("a=1; Path=/"));
        assertTrue(setCookies.contains("b=2; Path=/"));
    }

    @Test
    void functionSuppliedHeaderOverridesTheDefaultContentType() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();

        invocationRegistry.completeWith(
                "{\"status\":200,\"body\":\"<h1>hi</h1>\",\"headers\":{\"Content-Type\":\"text/html\"}}");
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertEquals("text/html", response.headers().get(HttpHeaderNames.CONTENT_TYPE));
    }

    @Test
    void functionCannotOverrideContentLengthOrTransferEncoding() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();

        invocationRegistry.completeWith(
                "{\"status\":200,\"body\":{\"ok\":true},"
                        + "\"headers\":{\"Content-Length\":\"999999\",\"Transfer-Encoding\":\"chunked\"}}");
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        int actualBodyLength = response.content().readableBytes();
        assertEquals(String.valueOf(actualBodyLength), response.headers().get(HttpHeaderNames.CONTENT_LENGTH));
        assertNull(response.headers().get(HttpHeaderNames.TRANSFER_ENCODING));
    }

    @Test
    void writesServerErrorWhenInvocationFails() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();

        invocationRegistry.failWith();
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
    }

    @Test
    void writesGatewayTimeoutWhenPendingCompletionTimesOut() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofMillis(50));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();

        awaitCondition(() -> pendingRegistry.pendingCount() == 0, Duration.ofSeconds(2));
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.GATEWAY_TIMEOUT, response.status());
    }

    @Test
    void duplicateCompletionDoesNotWriteASecondResponse() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();
        invocationRegistry.completeWith("{\"status\":200,\"body\":{\"ok\":true}}");

        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();
        FullHttpResponse first = channel.readOutbound();
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();
        FullHttpResponse second = channel.readOutbound();

        assertEquals(HttpResponseStatus.OK, first.status());
        assertNull(second);
    }

    @Test
    void alreadyTerminalInvocationCompletesImmediatelyWithoutWaitingForTerminalEvent() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        // The whole pipeline completed during the registration window - the
        // terminal NATS event was dropped before the pending entry existed.
        invocationRegistry.completeOnFirstFindWith("{\"status\":200,\"body\":{\"ok\":true}}");

        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();
        channel.runPendingTasks();

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.OK, response.status());
        JsonNode body = OBJECT_MAPPER.readTree(response.content().toString(StandardCharsets.UTF_8));
        assertTrue(body.at("/ok").asBoolean());
        assertEquals(0, pendingRegistry.pendingCount());
    }

    @Test
    void reconciliationWritesOnlyOnceWhenALateTerminalEventAlsoArrives() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry);
        invocationRegistry.completeOnFirstFindWith("{\"status\":200,\"body\":{\"ok\":true}}");

        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();
        channel.runPendingTasks();
        FullHttpResponse first = channel.readOutbound();
        // Duplicate terminal delivery (event + reconcile): must be a no-op.
        pendingRegistry.complete(INVOCATION_ID);
        channel.runPendingTasks();
        FullHttpResponse second = channel.readOutbound();

        assertEquals(HttpResponseStatus.OK, first.status());
        assertNull(second);
    }


    @Test
    void invocationCreationRunsOnTheDedicatedExecutorNotTheNettyEventLoopThread() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        invocationExecutor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "gateway-invocation-test"));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry, invocationExecutor);

        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();
        awaitChannelCondition(channel, () -> invocationRegistry.createdOnThread != null, Duration.ofSeconds(5));
        awaitChannelCondition(channel, () -> pendingRegistry.pendingCount() == 1, Duration.ofSeconds(5));

        assertTrue(invocationRegistry.createdOnThread.startsWith("gateway-invocation-test"));
        assertNull(channel.readOutbound());
    }

    @Test
    void lostWakeupReconciliationRunsOnTheDedicatedExecutorNotTheNettyEventLoopThread() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        invocationExecutor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "gateway-invocation-test"));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry, invocationExecutor);

        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();
        awaitChannelCondition(channel, () -> invocationRegistry.reconcileFindOnThread != null, Duration.ofSeconds(5));

        assertTrue(invocationRegistry.createdOnThread.startsWith("gateway-invocation-test"));
        assertTrue(invocationRegistry.reconcileFindOnThread.startsWith("gateway-invocation-test"));
    }

    @Test
    void terminalInvocationLookupRunsOnTheDedicatedExecutorAndResponseStillWritten() throws Exception {
        CapturingInvocationRegistry invocationRegistry = new CapturingInvocationRegistry();
        PendingInvocationResponseRegistry pendingRegistry = pendingRegistry(Duration.ofSeconds(10));
        invocationExecutor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "gateway-invocation-test"));
        EmbeddedChannel channel = channel(flowResolver(), invocationRegistry, pendingRegistry, invocationExecutor);

        channel.writeInbound(checkoutRequest());
        channel.runPendingTasks();
        awaitChannelCondition(channel, () -> pendingRegistry.pendingCount() == 1, Duration.ofSeconds(5));

        invocationRegistry.completeWith("{\"status\":201,\"body\":{\"ok\":true}}");
        pendingRegistry.complete(INVOCATION_ID);
        awaitChannelCondition(channel, () -> invocationRegistry.terminalFindOnThread != null, Duration.ofSeconds(5));

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.CREATED, response.status());
        JsonNode body = OBJECT_MAPPER.readTree(response.content().toString(StandardCharsets.UTF_8));
        assertTrue(body.at("/ok").asBoolean());
        assertTrue(invocationRegistry.terminalFindOnThread.startsWith("gateway-invocation-test"));
    }

    private CountingFlowResolver flowResolver() {
        return new CountingFlowResolver(new FlowResolution(FLOW_ID, "flw_checkout", FLOW_VERSION_ID));
    }

    private EmbeddedChannel channel(
            FlowResolver flowResolver,
            InvocationRegistry invocationRegistry,
            PendingInvocationResponseRegistry pendingRegistry
    ) {
        return channel(flowResolver, invocationRegistry, pendingRegistry, gatewayInvocationExecutor());
    }

    private EmbeddedChannel channel(
            FlowResolver flowResolver,
            InvocationRegistry invocationRegistry,
            PendingInvocationResponseRegistry pendingRegistry,
            ExecutorService invocationExecutor
    ) {
        GatewayRuntimeEntry gateway = new GatewayRuntimeEntry(
                GATEWAY_ID, "Primary Gateway", "a6n1y8", "funchole.test", "a6n1y8.funchole.test", null, null);
        GatewayRegistry registry = new GatewayRegistry(new GatewayRegistrySnapshot(
                Map.of(gateway.hostname(), gateway), null, Map.of()));
        GatewayHttpHandler handler =
                new GatewayHttpHandler(
                        OBJECT_MAPPER, registry, flowResolver, invocationRegistry, pendingRegistry, invocationExecutor, null, null,
                        FixedHostProxy.empty());
        return new EmbeddedChannel(handler);
    }

    private DefaultFullHttpRequest checkoutRequest() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                HttpMethod.POST,
                "/checkout",
                Unpooled.copiedBuffer("{\"total\":100}", StandardCharsets.UTF_8)
        );
        request.headers().set(HttpHeaderNames.HOST, "a6n1y8.funchole.test");
        return request;
    }

    private PendingInvocationResponseRegistry pendingRegistry(Duration timeout) {
        timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
        return new PendingInvocationResponseRegistry(timeoutExecutor, timeout);
    }

    private void awaitChannelCondition(
            EmbeddedChannel channel,
            BooleanSupplier condition,
            Duration timeout
    ) throws InterruptedException {
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadlineMillis) {
            channel.runPendingTasks();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        channel.runPendingTasks();
        if (!condition.getAsBoolean()) {
            throw new AssertionError("Condition not met within " + timeout);
        }
    }

    private void awaitCondition(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadlineMillis = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadlineMillis) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    private static final class CountingFlowResolver implements FlowResolver {
        private final FlowResolution resolution;
        private int calls;

        private CountingFlowResolver(FlowResolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public Optional<RouteMatch> resolve(GatewayRuntimeEntry gateway, GatewayRequestContext requestContext) {
            calls++;
            return Optional.of(new RouteMatch(resolution, Map.of()));
        }
    }

    private static final class CapturingInvocationRegistry implements InvocationRegistry {
        private CreateInvocationRequest request;
        private volatile InvocationStatus status = InvocationStatus.PENDING;
        private volatile String result;
        private volatile String resultOnFirstFind;
        private boolean findSeen;

        volatile String createdOnThread;
        volatile String reconcileFindOnThread;
        volatile String terminalFindOnThread;

        @Override
        public Invocation createDirectInvocation(DirectInvocationRequest request) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public Invocation create(CreateInvocationRequest request) {
            this.request = request;
            createdOnThread = Thread.currentThread().getName();
            return currentInvocation();
        }

        /** Simulates the complete pipeline finishing while the Gateway is registering. */
        void completeOnFirstFindWith(String result) {
            this.status = InvocationStatus.COMPLETED;
            this.resultOnFirstFind = result;
        }

        void completeWith(String result) {
            this.status = InvocationStatus.COMPLETED;
            this.result = result;
        }

        void failWith() {
            this.status = InvocationStatus.FAILED;
        }

        private Invocation currentInvocation() {
            String effectiveResult = result != null || resultOnFirstFind == null ? result : resultOnFirstFind;
            return new Invocation(
                    INVOCATION_ID,
                    InvocationKind.FLOW,
                    request.flowId(),
                    request.flowKey(),
                    request.flowVersionId(),
                    null,
                    null,
                    null,
                    status,
                    request.inputPayload(),
                    "{}",
                    effectiveResult,
                    null,
                    OffsetDateTime.now(),
                    OffsetDateTime.now(),
                    status == InvocationStatus.PENDING ? null : OffsetDateTime.now()
            );
        }

        @Override
        public Optional<Invocation> findById(UUID invocationId) {
            if (request == null || !invocationId.equals(INVOCATION_ID)) {
                return Optional.empty();
            }
            boolean firstFind = !findSeen;
            findSeen = true;
            if (firstFind) {
                if (resultOnFirstFind != null) {
                    result = resultOnFirstFind;
                }
                reconcileFindOnThread = Thread.currentThread().getName();
            } else {
                terminalFindOnThread = Thread.currentThread().getName();
            }
            return Optional.of(currentInvocation());
        }

        @Override
        public InvocationTransition markCompleted(UUID invocationId, String result) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public InvocationTransition markFailed(UUID invocationId, String error) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
