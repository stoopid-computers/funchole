package com.funchole.backend.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.invocation.Invocation;
import com.funchole.backend.invocation.InvocationKind;
import com.funchole.backend.invocation.InvocationMessagingConfig;
import com.funchole.backend.invocation.InvocationReadyEvent;
import com.funchole.backend.invocation.InvocationRegistry;
import com.funchole.backend.invocation.InvocationSnapshot;
import com.funchole.backend.invocation.InvocationStatus;
import com.funchole.backend.invocation.InvocationTransition;
import com.funchole.backend.runtimeregistry.RuntimeInstance;
import com.funchole.backend.runtimeregistry.RuntimeRegistry;
import com.funchole.backend.runtimeregistry.RuntimeRequirement;
import com.funchole.backend.runtimeregistry.RuntimeTarget;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InvocationDispatcher {
    private static final Logger logger = LoggerFactory.getLogger(InvocationDispatcher.class);
    private static final String RESPONSE_COMPONENT_TYPE = "RESPONSE";
    private static final String RUNTIME_EXECUTION_FAILED_ERROR_CODE = "RUNTIME_EXECUTION_FAILED";

    private final Connection connection;
    private final InvocationRegistry invocationRegistry;
    private final InvocationStepExecutionRegistry stepExecutionRegistry;
    private final RuntimeRegistry runtimeRegistry;
    private final ObjectMapper objectMapper;
    private final InvocationSnapshotValidator snapshotValidator;
    private final ExecutionPlanner executionPlanner;
    private final RuntimeExecutionGateway executionGateway;
    private final FunctionVersionEnvironmentResolver environmentResolver;
    private final FunctionVersionDatabaseResolver databaseResolver;
    private final InvocationStepExecutionLogRegistry stepExecutionLogRegistry;
    private final TenantResolver tenantResolver;
    private final JetStreamSubscription subscription;

    /**
     * Terminal RESULT/ERROR messages are correlated and completed on the IPC
     * transport's own reader thread (see {@link IpcRuntimeExecutionGateway}).
     * That thread must stay free to keep decoding/correlating frames for
     * other in-flight executions, so the blocking JDBC terminal-state
     * transition (and the runtime capacity release that follows it) is
     * dispatched onto this small, bounded, dedicated pool instead of running
     * inline on whichever thread completes the completion future.
     */
    private final ExecutorService completionExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "dispatcher-completion");
        thread.setDaemon(true);
        return thread;
    });

    public InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            RuntimeRegistry runtimeRegistry
    ) {
        this(connection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                new ExecutionPlanner(), new InMemoryRuntimeExecutionGateway());
    }

    InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            RuntimeRegistry runtimeRegistry,
            ExecutionPlanner executionPlanner
    ) {
        this(connection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                executionPlanner, new InMemoryRuntimeExecutionGateway());
    }

    /**
     * Public so a caller assembling every real component in-process (e.g. an
     * end-to-end test standing up a real Dispatcher against a real
     * {@link IpcRuntimeExecutionGateway} and Runtime Worker, mirroring what
     * {@code DispatcherMain} already does) can inject a real
     * {@link RuntimeExecutionGateway} without a test living in this package.
     */
    public InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            RuntimeRegistry runtimeRegistry,
            ExecutionPlanner executionPlanner,
            RuntimeExecutionGateway executionGateway
    ) {
        this(connection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                executionPlanner, executionGateway, new NoopFunctionVersionEnvironmentResolver());
    }

    public InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            RuntimeRegistry runtimeRegistry,
            ExecutionPlanner executionPlanner,
            RuntimeExecutionGateway executionGateway,
            FunctionVersionEnvironmentResolver environmentResolver
    ) {
        this(connection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                executionPlanner, executionGateway, environmentResolver, new NoopInvocationStepExecutionLogRegistry());
    }

    /**
     * Full constructor, additionally wiring durable step-execution log
     * storage (F248) - every {@link RuntimeLogEntry} the runtime worker
     * streams during an execution is persisted through
     * {@code stepExecutionLogRegistry} as it arrives, keyed by the step
     * execution's own id (the same UUID as the execution's IPC
     * {@code executionId} - see {@link RuntimeExecutionRequest#of}).
     */
    public InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            RuntimeRegistry runtimeRegistry,
            ExecutionPlanner executionPlanner,
            RuntimeExecutionGateway executionGateway,
            FunctionVersionEnvironmentResolver environmentResolver,
            InvocationStepExecutionLogRegistry stepExecutionLogRegistry
    ) {
        this(connection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                executionPlanner, executionGateway, environmentResolver, new NoopFunctionVersionDatabaseResolver(),
                stepExecutionLogRegistry);
    }

    /**
     * Full constructor, additionally wiring the shared-Database resolver:
     * every FUNCTION step execution's warm connections (F-database-resource,
     * see {@code Database}/{@code FunctionVersionDatabaseAttachment} in the
     * controlplane module) are resolved here, alongside env vars/secrets, and
     * handed to the Runtime Worker over the same IPC payload.
     */
    public InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            RuntimeRegistry runtimeRegistry,
            ExecutionPlanner executionPlanner,
            RuntimeExecutionGateway executionGateway,
            FunctionVersionEnvironmentResolver environmentResolver,
            FunctionVersionDatabaseResolver databaseResolver,
            InvocationStepExecutionLogRegistry stepExecutionLogRegistry
    ) {
        this(connection, invocationRegistry, stepExecutionRegistry, runtimeRegistry,
                executionPlanner, executionGateway, environmentResolver, databaseResolver,
                stepExecutionLogRegistry, new NoopTenantResolver());
    }

    /** Full constructor, additionally telling the runtime which tenant owns each execution. */
    public InvocationDispatcher(
            Connection connection,
            InvocationRegistry invocationRegistry,
            InvocationStepExecutionRegistry stepExecutionRegistry,
            RuntimeRegistry runtimeRegistry,
            ExecutionPlanner executionPlanner,
            RuntimeExecutionGateway executionGateway,
            FunctionVersionEnvironmentResolver environmentResolver,
            FunctionVersionDatabaseResolver databaseResolver,
            InvocationStepExecutionLogRegistry stepExecutionLogRegistry,
            TenantResolver tenantResolver
    ) {
        this.tenantResolver = tenantResolver;
        this.connection = connection;
        this.invocationRegistry = invocationRegistry;
        this.stepExecutionRegistry = stepExecutionRegistry;
        this.runtimeRegistry = runtimeRegistry;
        this.objectMapper = new ObjectMapper();
        this.snapshotValidator = new InvocationSnapshotValidator();
        this.executionPlanner = executionPlanner;
        this.executionGateway = executionGateway;
        this.environmentResolver = environmentResolver;
        this.databaseResolver = databaseResolver;
        this.stepExecutionLogRegistry = stepExecutionLogRegistry;
        ensureStream();
        this.subscription = subscribe();
    }

    /**
     * Kind-aware identity label for logs: FLOW invocations carry Flow identity,
     * DIRECT_FUNCTION invocations carry the explicit pinned function identity
     * (their Flow columns are null by design).
     */
    private static String invocationIdentityLabel(Invocation invocation) {
        return invocation.kind() == InvocationKind.DIRECT_FUNCTION
                ? "kind=DIRECT_FUNCTION, functionKey=" + invocation.functionKey()
                        + ", functionVersionId=" + invocation.functionVersionId()
                : "kind=FLOW, flowKey=" + invocation.flowKey()
                        + ", flowVersionId=" + invocation.flowVersionId();
    }

    public boolean processNext(Duration timeout) {
        try {
            List<Message> messages = subscription.fetch(1, timeout);
            if (messages.isEmpty()) {
                return false;
            }

            Message message = messages.getFirst();
            process(message);
            message.ack();
            return true;
        } catch (Exception exception) {
            logger.warn("Dispatcher failed to process invocation-ready message: {}", exception.getMessage());
            return false;
        }
    }

    private void process(Message message) throws IOException {
        InvocationReadyEvent event = objectMapper.readValue(
                new String(message.getData(), StandardCharsets.UTF_8),
                InvocationReadyEvent.class
        );
        if (!InvocationReadyEvent.EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalStateException("Unsupported invocation event type: " + event.eventType());
        }

        Invocation invocation = invocationRegistry
                .findById(event.invocationId())
                .orElseThrow(() -> new IllegalStateException("Invocation not found: " + event.invocationId()));
        if (invocation.status() != InvocationStatus.PENDING) {
            throw new IllegalStateException("Invocation is not PENDING: " + invocation.invocationId());
        }

        InvocationSnapshot snapshot = objectMapper.readValue(invocation.dependencySnapshot(), InvocationSnapshot.class);
        InvocationValidationResult validationResult = snapshotValidator.validate(snapshot);
        if (!validationResult.valid()) {
            throw new IllegalStateException("Invocation snapshot is not executable-shaped: " + validationResult.errors());
        }

        DispatchableStep dispatchableStep = executionPlanner.planInitialStep(invocation, snapshot);
        logger.info(
                "Invocation planned: invocationId={}, identity={}, stepId={}, stepKey={}, position={}, componentType={}, componentId={}, componentVersionId={}",
                dispatchableStep.invocationId(),
                invocationIdentityLabel(invocation),
                dispatchableStep.stepId(),
                dispatchableStep.stepKey(),
                dispatchableStep.position(),
                dispatchableStep.componentType(),
                dispatchableStep.componentId(),
                dispatchableStep.componentVersionId()
        );

        InvocationStepExecution stepExecution = stepExecutionRegistry.createOrGetReadyExecution(dispatchableStep);
        if (stepExecution.status() != InvocationStepExecutionStatus.READY) {
            throw new IllegalStateException("Step execution is not READY: " + stepExecution.id());
        }
        logger.info(
                "Step execution ready: executionId={}, invocationId={}, stepId={}, position={}, componentId={}, componentVersionId={}, attempt={}, status={}",
                stepExecution.id(),
                stepExecution.invocationId(),
                stepExecution.stepId(),
                stepExecution.position(),
                stepExecution.componentId(),
                stepExecution.componentVersionId(),
                stepExecution.attempt(),
                stepExecution.status()
        );

        dispatchStepExecution(stepExecution, invocation.inputPayload());
    }

    /**
     * The single dispatch path shared by the initial planned step and every
     * sequentially progressed FUNCTION step: reserve runtime capacity, build
     * the execution request, hand off to the runtime worker, persist RUNNING
     * on acceptance, and register terminal-completion handling.
     *
     * {@code stepInput} is always supplied explicitly by the caller - the
     * flow's first step receives the root Invocation input payload, and every
     * later step receives the previous step's stored result. Nothing here
     * infers that from {@code position}.
     */
    private void dispatchStepExecution(InvocationStepExecution stepExecution, String stepInput) {
        RuntimeRequirement runtimeRequirement = new RuntimeRequirement(stepExecution.runtimeType());
        RuntimeTarget runtimeTarget = runtimeRegistry.selectAndReserve(runtimeRequirement);
        logger.info(
                "Runtime selected: executionId={}, invocationId={}, stepId={}, runtimeInstanceId={}, runtimeType={}",
                stepExecution.id(),
                stepExecution.invocationId(),
                stepExecution.stepId(),
                runtimeTarget.runtimeInstanceId(),
                runtimeTarget.runtimeType()
        );
        try {
            Map<String, String> environment = environmentResolver.resolve(stepExecution);
            List<DatabaseConnectionInfo> databases = databaseResolver.resolve(stepExecution);
            RuntimeExecutionRequest executionRequest = RuntimeExecutionRequest.of(stepExecution, stepInput, environment, databases, tenantResolver.resolve(stepExecution));
            UUID stepExecutionId = stepExecution.id();
            RuntimeExecutionHandle handle = executionGateway.handoff(runtimeTarget, executionRequest,
                    logEntry -> stepExecutionLogRegistry.append(stepExecutionId, logEntry.stream(), logEntry.message()));
            RuntimeExecutionAcceptance acceptance = handle.acceptance();
            if (!acceptance.accepted()) {
                throw new IllegalStateException("Runtime execution handoff rejected: " + acceptance.rejectionReason());
            }
            stepExecution = stepExecutionRegistry.markRunning(stepExecution.id(), runtimeTarget.runtimeInstanceId());
            registerTerminalCompletion(stepExecution, runtimeTarget, handle);
        } catch (RuntimeException handoffFailure) {
            runtimeRegistry.release(runtimeTarget.runtimeInstanceId());
            throw handoffFailure;
        }

        RuntimeInstance selectedInstance = runtimeRegistry.find(runtimeTarget.runtimeInstanceId()).orElse(null);
        logger.info(
                "Runtime execution accepted: executionId={}, invocationId={}, stepId={}, componentId={}, componentVersionId={}, runtimeInstanceId={}, runtimeType={}, attempt={}, status={}, inFlight={}, capacity={}",
                stepExecution.id(),
                stepExecution.invocationId(),
                stepExecution.stepId(),
                stepExecution.componentId(),
                stepExecution.componentVersionId(),
                runtimeTarget.runtimeInstanceId(),
                runtimeTarget.runtimeType(),
                stepExecution.attempt(),
                stepExecution.status(),
                selectedInstance == null ? "?" : selectedInstance.inFlight(),
                selectedInstance == null ? "?" : selectedInstance.capacity()
        );
    }

    private void registerTerminalCompletion(
            InvocationStepExecution stepExecution,
            RuntimeTarget runtimeTarget,
            RuntimeExecutionHandle handle
    ) {
        handle.completion().whenCompleteAsync((result, failure) -> {
            if (failure != null) {
                // The handle was ACCEPTED and then the transport/worker died
                // (IPC close, process exit). Without a terminal message the
                // step would stay RUNNING and the reserved slot leaked, so
                // synthesize the smallest infrastructure ERROR payload and
                // run it through the same terminal pipeline.
                logger.warn(
                        "Runtime completion failed before terminal message: executionId={}, runtimeInstanceId={}, message={}",
                        stepExecution.id(),
                        runtimeTarget.runtimeInstanceId(),
                        failure.getMessage()
                );
                handleTerminalResult(
                        RuntimeExecutionResult.failure(
                                stepExecution.id(),
                                new RuntimeExecutionError(
                                        RUNTIME_EXECUTION_FAILED_ERROR_CODE, describeFailure(failure))),
                        runtimeTarget);
                return;
            }
            handleTerminalResult(result, runtimeTarget);
        }, completionExecutor);
    }

    private String describeFailure(Throwable failure) {
        String message = failure.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return failure.getClass().getSimpleName();
    }

    private void handleTerminalResult(RuntimeExecutionResult result, RuntimeTarget runtimeTarget) {
        try {
            InvocationStepExecutionTransition transition = persistStepTerminal(result);
            if (transition.transitioned()) {
                runtimeRegistry.release(runtimeTarget.runtimeInstanceId());
            }
            logStepTerminal(transition, result, runtimeTarget.runtimeInstanceId());
            if (transition.transitioned()) {
                onStepTerminal(transition.execution());
            }
        } catch (RuntimeException exception) {
            logger.warn(
                    "Runtime terminal persistence failed: executionId={}, runtimeInstanceId={}, message={}",
                    result.executionId(),
                    runtimeTarget.runtimeInstanceId(),
                    exception.getMessage()
            );
        }
    }

    private InvocationStepExecutionTransition persistStepTerminal(RuntimeExecutionResult result) {
        return switch (result.terminalType()) {
            case RESULT -> stepExecutionRegistry.markCompleted(result.executionId(), result);
            case ERROR -> stepExecutionRegistry.markFailed(result.executionId(), result);
        };
    }

    private void logStepTerminal(InvocationStepExecutionTransition transition, RuntimeExecutionResult result, String runtimeInstanceId) {
        InvocationStepExecution execution = transition.execution();
        if (execution.status() == InvocationStepExecutionStatus.COMPLETED) {
            logger.info(
                    "Runtime execution completed: executionId={}, status={}, runtimeInstanceId={}, capacityReleased={}",
                    execution.id(),
                    execution.status(),
                    runtimeInstanceId,
                    transition.transitioned()
            );
        } else {
            logger.info(
                    "Runtime execution failed: executionId={}, status={}, errorCode={}, runtimeInstanceId={}, capacityReleased={}",
                    execution.id(),
                    execution.status(),
                    result.error() == null ? "UNKNOWN" : result.error().code(),
                    runtimeInstanceId,
                    transition.transitioned()
            );
        }
    }

    /**
     * Routes a step's durable terminal state to whatever happens next:
     * <ul>
     *   <li>FAILED (any component type) - the Invocation becomes FAILED.</li>
     *   <li>COMPLETED RESPONSE step - the Invocation becomes COMPLETED, using
     *       the RESPONSE step's own result as the final response.</li>
     *   <li>COMPLETED step of a DIRECT_FUNCTION invocation - the Invocation
     *       becomes COMPLETED immediately using that step's own result.
     *       {@link com.funchole.backend.invocation.JdbcInvocationRegistry}
     *       always synthesizes exactly one FUNCTION-typed step for a direct
     *       invocation (there is no RESPONSE step to designate the flow's
     *       end, because there is no flow), so without this branch a direct
     *       invocation's own step would complete durably while the
     *       Invocation itself stayed PENDING forever - GAP-17.</li>
     *   <li>COMPLETED FUNCTION step of a FLOW invocation - flow progression
     *       continues to the next ordered step.</li>
     * </ul>
     */
    private void onStepTerminal(InvocationStepExecution execution) {
        if (execution.status() == InvocationStepExecutionStatus.FAILED) {
            failInvocation(execution);
            return;
        }
        if (execution.status() != InvocationStepExecutionStatus.COMPLETED) {
            return;
        }
        if (isResponseStep(execution) || isDirectFunctionInvocation(execution)) {
            completeInvocation(execution);
        } else {
            planAndDispatchNextStep(execution);
        }
    }

    private boolean isDirectFunctionInvocation(InvocationStepExecution execution) {
        return invocationRegistry.findById(execution.invocationId())
                .map(invocation -> invocation.kind() == InvocationKind.DIRECT_FUNCTION)
                .orElse(false);
    }

    private boolean isResponseStep(InvocationStepExecution execution) {
        return RESPONSE_COMPONENT_TYPE.equalsIgnoreCase(execution.componentType());
    }

    /**
     * After a step completes durably, finds the next ordered step of the same
     * flow from the frozen Invocation snapshot and dispatches it through the
     * Runtime Registry/IPC - FUNCTION, RESPONSE, and MIDDLEWARE all execute
     * identically here; RESPONSE is distinguished only by
     * {@link #onStepTerminal} treating its completion as the end of the whole
     * invocation. Passes the previous step's stored result as input. Stops
     * silently when there is no further progressable step - a flow that ends
     * without a RESPONSE step leaves its Invocation PENDING, unchanged from
     * the previous milestone.
     */
    private void planAndDispatchNextStep(InvocationStepExecution completedExecution) {
        Invocation invocation = invocationRegistry
                .findById(completedExecution.invocationId())
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot progress flow: invocation not found: " + completedExecution.invocationId()));
        InvocationSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(invocation.dependencySnapshot(), InvocationSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot progress flow: invalid snapshot for invocation "
                    + completedExecution.invocationId(), exception);
        }

        Optional<DispatchableStep> nextStep =
                executionPlanner.planNextStep(invocation, snapshot, completedExecution.position());
        if (nextStep.isEmpty()) {
            Optional<String> nextType =
                    executionPlanner.nextStepComponentType(snapshot, completedExecution.position());
            if (nextType.isPresent()) {
                failInvocationWithUnsupportedComponentType(
                        completedExecution, invocation, nextType.get());
                return;
            }
            logger.info(
                    "Flow progression stopped: no further step after position={} for invocationId={}, identity={}",
                    completedExecution.position(),
                    invocation.invocationId(),
                    invocationIdentityLabel(invocation)
            );
            return;
        }

        InvocationStepExecution nextExecution = stepExecutionRegistry.createOrGetReadyExecution(nextStep.get());
        if (nextExecution.status() != InvocationStepExecutionStatus.READY) {
            logger.info(
                    "Next step execution {} is already {} - skipping duplicate progression dispatch for stepId={}, attempt={}",
                    nextExecution.id(),
                    nextExecution.status(),
                    nextExecution.stepId(),
                    nextExecution.attempt()
            );
            return;
        }
        logger.info(
                "Next step planned: executionId={}, invocationId={}, stepId={}, position={}, componentType={}, componentId={}, componentVersionId={}, attempt={}, status={}",
                nextExecution.id(),
                nextExecution.invocationId(),
                nextExecution.stepId(),
                nextExecution.position(),
                nextExecution.componentType(),
                nextExecution.componentId(),
                nextExecution.componentVersionId(),
                nextExecution.attempt(),
                nextExecution.status()
        );

        dispatchStepExecution(nextExecution, completedExecution.result());
    }

    private void completeInvocation(InvocationStepExecution responseExecution) {
        try {
            InvocationTransition transition =
                    invocationRegistry.markCompleted(responseExecution.invocationId(), responseExecution.result());
            logger.info(
                    "Invocation completed: invocationId={}, published={}",
                    responseExecution.invocationId(), transition.transitioned()
            );
        } catch (RuntimeException exception) {
            logger.warn(
                    "Failed to persist Invocation completion: invocationId={}, message={}",
                    responseExecution.invocationId(), exception.getMessage()
            );
        }
    }

    private void failInvocation(InvocationStepExecution failedExecution) {
        try {
            InvocationTransition transition =
                    invocationRegistry.markFailed(failedExecution.invocationId(), failedExecution.error());
            logger.info(
                    "Invocation failed: invocationId={}, published={}",
                    failedExecution.invocationId(), transition.transitioned()
            );
        } catch (RuntimeException exception) {
            logger.warn(
                    "Failed to persist Invocation failure: invocationId={}, message={}",
                    failedExecution.invocationId(), exception.getMessage()
            );
        }
    }

    /**
     * A next step exists in the immutable snapshot but its component type is
     * not one this system currently supports. Fail the Invocation rather than
     * leaving it PENDING indefinitely.
     */
    private void failInvocationWithUnsupportedComponentType(
            InvocationStepExecution completedExecution,
            Invocation invocation,
            String unsupportedComponentType) {
        String errorMessage = "Unsupported component type '" + unsupportedComponentType
                + "' at position following " + completedExecution.position()
                + " for invocationId=" + invocation.invocationId()
                + ", identity=" + invocationIdentityLabel(invocation);
        logger.info("Flow progression failed: {}", errorMessage);
        try {
            invocationRegistry.markFailed(invocation.invocationId(), serializeErrorAsJson(errorMessage));
        } catch (RuntimeException exception) {
            logger.warn(
                    "Failed to persist Invocation failure: invocationId={}, message={}",
                    invocation.invocationId(), exception.getMessage()
            );
        }
    }

    private String serializeErrorAsJson(String errorMessage) {
        try {
            return objectMapper.writeValueAsString(Map.of("message", errorMessage));
        } catch (Exception exception) {
            return "{\"message\":\"" + errorMessage.replace("\"", "\\\"") + "\"}";
        }
    }

    /**
     * Stops accepting new terminal completions. Intended for orderly
     * Dispatcher shutdown; safe to skip since the pool only holds daemon
     * threads.
     */
    public void close() {
        completionExecutor.shutdown();
        try {
            completionExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private JetStreamSubscription subscribe() {
        try {
            JetStream jetStream = connection.jetStream();
            PullSubscribeOptions options = PullSubscribeOptions.builder()
                    .stream(InvocationMessagingConfig.STREAM_NAME)
                    .durable(InvocationMessagingConfig.DISPATCHER_DURABLE)
                    .build();
            JetStreamSubscription jetStreamSubscription = jetStream.subscribe(
                    InvocationMessagingConfig.INVOCATION_READY_SUBJECT,
                    options
            );
            connection.flush(Duration.ofSeconds(5));
            return jetStreamSubscription;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to subscribe to invocation-ready events", exception);
        } catch (IOException | JetStreamApiException | TimeoutException exception) {
            throw new IllegalStateException("Failed to subscribe to invocation-ready events", exception);
        }
    }

    private void ensureStream() {
        try {
            var management = connection.jetStreamManagement();
            StreamConfiguration configuration = StreamConfiguration.builder()
                    .name(InvocationMessagingConfig.STREAM_NAME)
                    .subjects(
                            InvocationMessagingConfig.INVOCATION_READY_SUBJECT,
                            InvocationMessagingConfig.INVOCATION_TERMINAL_SUBJECT
                    )
                    .storageType(StorageType.File)
                    .build();
            try {
                management.getStreamInfo(InvocationMessagingConfig.STREAM_NAME);
                management.updateStream(configuration);
            } catch (JetStreamApiException exception) {
                management.addStream(configuration);
            }
        } catch (IOException | JetStreamApiException exception) {
            throw new IllegalStateException("Failed to ensure invocation JetStream stream", exception);
        }
    }
}
