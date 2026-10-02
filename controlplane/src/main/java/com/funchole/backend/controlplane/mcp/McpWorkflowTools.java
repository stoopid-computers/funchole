package com.funchole.backend.controlplane.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.controlplane.constant.FlowStepComponentType;
import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.dto.DirectFlowInvocationResponse;
import com.funchole.backend.controlplane.dto.DirectInvocationResponse;
import com.funchole.backend.controlplane.dto.FlowStepResponse;
import com.funchole.backend.controlplane.dto.FlowVersionResponse;
import com.funchole.backend.controlplane.dto.FunctionResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionConfigResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionResponse;
import com.funchole.backend.controlplane.entity.SourceFile;
import com.funchole.backend.controlplane.service.workflow.BuildFunctionUseCase;
import com.funchole.backend.controlplane.service.workflow.ComposeFlowUseCase;
import jakarta.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/** Curated authoring workflows. References are routing hints; facades enforce ownership. */
@Service
public class McpWorkflowTools {
    public enum Runtime { NODE, STATIC }
    public record BuildRequest(@Nullable String functionRef, @Nullable String baseVersionRef,
                               @Nullable String key, @Nullable String name,
                               @Nullable Runtime runtime, String entrypoint, @Nullable String handler,
                               List<FunctionVersionMcpTools.SourceFileInput> files,
                               @Nullable Map<String, String> env, @Nullable Map<String, String> secrets,
                               @Nullable List<String> databases, @Nullable List<String> removeDatabases) { }
    public record StepInput(String key, FlowStepComponentType type, String reference, @Nullable String metadata) { }
    public record ComposeRequest(@Nullable String flowRef, @Nullable String key, @Nullable String name,
                                 @Nullable String gatewayRef, @Nullable String httpMethod, @Nullable String path,
                                 @Nullable Integer priority, @Nullable Runtime runtime,
                                 @Nullable String componentRef, @Nullable List<StepInput> steps) { }
    public record RouteInput(String gatewayRef, String httpMethod, String path, @Nullable Integer priority) { }
    public record PublishRequest(String reference, String expectedActiveVersionRef, @Nullable RouteInput route) { }

    private final FunctionMcpTools functions;
    private final FunctionVersionMcpTools versions;
    private final FlowMcpTools flows;
    private final FlowVersionMcpTools flowVersions;
    private final DatabaseMcpTools databases;
    private final GatewayMcpTools gateways;
    private final InvocationMcpTools invocations;
    private final McpFlowPublication publication;
    private final BuildFunctionUseCase buildFunctionUseCase;
    private final ComposeFlowUseCase composeFlowUseCase;
    private final ObjectMapper json = new ObjectMapper();

    public McpWorkflowTools(FunctionMcpTools functions, FunctionVersionMcpTools versions,
                            FlowMcpTools flows, FlowVersionMcpTools flowVersions,
                            DatabaseMcpTools databases, GatewayMcpTools gateways,
                            InvocationMcpTools invocations, McpFlowPublication publication,
                            BuildFunctionUseCase buildFunctionUseCase, ComposeFlowUseCase composeFlowUseCase) {
        this.functions = functions;
        this.versions = versions;
        this.flows = flows;
        this.flowVersions = flowVersions;
        this.databases = databases;
        this.gateways = gateways;
        this.invocations = invocations;
        this.publication = publication;
        this.buildFunctionUseCase = buildFunctionUseCase;
        this.composeFlowUseCase = composeFlowUseCase;
    }

    @McpTool(name = "build_function", description = "Replace complete source on a new revision and start an asynchronous build. Existing Functions require an explicit owned base, including FAILED bases. Omitted config is inherited; database additions are additive. Trusted code only: builds have unsandboxed host access. Does not publish live traffic.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public McpOperationResult buildFunction(@McpToolParam(description = "Complete source and revision configuration", required = true) BuildRequest request) {
        return McpOperationResult.run(() -> build(request));
    }

    private McpOperationResult build(BuildRequest r) throws Exception {
        require(r != null);
        require(r.files() == null || r.files().stream().noneMatch(java.util.Objects::isNull));
        McpReference function = r.functionRef() == null ? null : McpReference.parse(r.functionRef()).require("functions");
        McpReference base = r.baseVersionRef() == null ? null : McpReference.parse(r.baseVersionRef()).require("function-versions");
        require(base == null || function != null && function.id().equals(base.parentId()));
        var result = buildFunctionUseCase.execute(CurrentMcpUser.id(), new BuildFunctionUseCase.Command(
                function == null ? null : function.id(), base == null ? null : base.id(), r.key(), r.name(),
                r.runtime() == null ? null : r.runtime().name(), r.entrypoint(), r.handler(),
                r.files() == null ? null : r.files().stream().map(f -> new SourceFile(f.path(), f.content())).toList(),
                r.env(), r.secrets(), databaseRefs(r.databases()).stream().map(McpReference::id).toList(),
                databaseRefs(r.removeDatabases()).stream().map(McpReference::id).toList()));
        String survivor = result.versionId() == null ? McpReference.of("functions", result.functionId())
                : McpReference.version("function-versions", result.functionId(), result.versionId());
        if (result.partialFailure()) return McpOperationResult.failure("PARTIAL_FAILURE",
                "Preparation failed after creating state. Read the surviving resource before retrying.", survivor);
        return new McpOperationResult(true, "OK", "Build started; read state until READY or FAILED. Read the buildLogs reference with view=logs for diagnostics.", survivor, result.version(),
                Map.of("state", survivor, "buildLogs", survivor, "guide", "funchole://guides/" + result.version().runtime().toLowerCase(Locale.ROOT)),
                List.of("Builds and handlers have unsandboxed host access. Submit trusted code only."));
    }

    @McpTool(name = "compose_flow", description = "Create a complete transactional draft from pinned READY Functions or ADOPTED subflows. Exactly one componentRef or ordered steps is required. Single NODE uses RESPONSE; single STATIC uses FUNCTION. Existing routes and shared bindings remain unchanged.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public McpOperationResult composeFlow(@McpToolParam(description = "Flow identity and complete pinned composition", required = true) ComposeRequest request) {
        return McpOperationResult.run(() -> {
            require(request != null);
            McpReference flow = null;
            UUID gatewayId = null;
            if (request.flowRef() == null) {
                identity(request.key(), request.name());
                gatewayId = validateRoute(new RouteInput(request.gatewayRef(), request.httpMethod(), request.path(), request.priority()));
            } else {
                require(request.key() == null && request.name() == null && request.gatewayRef() == null
                        && request.httpMethod() == null && request.path() == null && request.priority() == null);
                flow = McpReference.parse(request.flowRef()).require("flows");
                flows.getFlow(flow.id().toString());
            }
            require((request.componentRef() != null) != (request.steps() != null));
            Runtime runtime = request.runtime();
            List<StepInput> steps = request.steps();
            if (request.componentRef() != null) {
                McpReference component = McpReference.parse(request.componentRef());
                Runtime componentRuntime = componentRuntime(component, component.kind().equals("flow-versions") ? FlowStepComponentType.SUB_FLOW : FlowStepComponentType.FUNCTION);
                if (runtime == null) runtime = componentRuntime;
                FlowStepComponentType type = component.kind().equals("flow-versions") ? FlowStepComponentType.SUB_FLOW
                        : runtime == Runtime.STATIC ? FlowStepComponentType.FUNCTION : FlowStepComponentType.RESPONSE;
                steps = List.of(new StepInput("main", type, request.componentRef(), null));
            }
            require(runtime != null);
            validateSteps(runtime, steps, new HashSet<>());
            List<ComposeFlowUseCase.Step> commandSteps = steps.stream().map(step -> {
                McpReference ref = McpReference.parse(step.reference());
                return new ComposeFlowUseCase.Step(step.key(), step.type(), ref.parentId(), ref.id(), step.metadata());
            }).toList();
            var composed = composeFlowUseCase.execute(CurrentMcpUser.id(), new ComposeFlowUseCase.Command(
                    flow == null ? null : flow.id(), request.key(), request.name(), gatewayId,
                    request.httpMethod(), request.path(), request.priority(), runtime.name(), commandSteps));
            FlowVersionResponse receipt = flowVersions.getFlowVersion(composed.flowId().toString(), composed.versionId().toString());
            String ref = McpReference.version("flow-versions", receipt.flowId(), receipt.id());
            return new McpOperationResult(true, "OK", "Draft prepared; no live revision was adopted.", ref, receipt,
                    Map.of("state", ref, "flow", McpReference.of("flows", receipt.flowId()), "guide", "funchole://guides/flows"), List.of());
        });
    }

    @McpTool(name = "invoke", description = "Execute a READY Function or executable Flow revision asynchronously. Execution is unsandboxed and may mutate application data or call external systems. STATIC must be checked through the Gateway, not invoked.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public McpOperationResult invoke(@McpToolParam(description = "Pinned function-version or flow-version reference", required = true) String reference,
                                    @McpToolParam(description = "Raw JSON payload, defaults to {}", required = false) String input) {
        return McpOperationResult.run(() -> {
            if (input != null) validateJson(input);
            McpReference ref = McpReference.parse(reference);
            Object receipt;
            UUID invocationId;
            if (ref.kind().equals("function-versions")) {
                require(componentRuntime(ref, FlowStepComponentType.RESPONSE) == Runtime.NODE);
                DirectInvocationResponse result = invocations.invokeFunctionVersion(ref.parentId().toString(), ref.id().toString(), input);
                receipt = result;
                invocationId = result.invocationId();
            } else {
                ref.require("flow-versions");
                validateExecutableFlow(ref, new HashSet<>(), false);
                DirectFlowInvocationResponse result = invocations.invokeFlowVersion(ref.parentId().toString(), ref.id().toString(), input);
                receipt = result;
                invocationId = result.invocationId();
            }
            return new McpOperationResult(true, "OK", "Invocation started.", McpReference.of("invocations", invocationId), receipt,
                    Map.of("state", McpReference.of("invocations", invocationId)), List.of("Execution may change application data."));
        });
    }

    @McpTool(name = "publish_flow", description = "Adopt an exact draft for live traffic. Set expectedActiveVersionRef to null when there is no active revision; otherwise provide its exact flow-version reference. Optional route edits occur atomically with adoption. Stale expectations fail without mutation. Verify real HTTPS afterward.", generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = true))
    public McpOperationResult publishFlow(@McpToolParam(description = "Exact draft, expectedActiveVersionRef null for no active revision or exact reference otherwise, optional route", required = true) PublishRequest request) {
        return McpOperationResult.run(() -> {
            require(request != null);
            McpReference target = McpReference.parse(request.reference()).require("flow-versions");
            UUID expected = null;
            if (request.expectedActiveVersionRef() != null) {
                McpReference prior = McpReference.parse(request.expectedActiveVersionRef()).require("flow-versions");
                require(prior.parentId().equals(target.parentId()));
                flowVersions.getFlowVersion(prior.parentId().toString(), prior.id().toString());
                expected = prior.id();
            }
            FlowVersionResponse draft = flowVersions.getFlowVersion(target.parentId().toString(), target.id().toString());
            require(draft.status() == FlowVersionStatus.DRAFT);
            validateSteps(Runtime.valueOf(draft.runtime()), toSteps(draft), new HashSet<>());
            UUID gateway = request.route() == null ? null : validateRoute(request.route());
            return publication.publish(target, expected, request.route(), gateway);
        });
    }

    private Runtime componentRuntime(McpReference ref, FlowStepComponentType type) {
        require(type != null);
        if (type == FlowStepComponentType.SUB_FLOW) {
            ref.require("flow-versions");
            FlowVersionResponse version = flowVersions.getFlowVersion(ref.parentId().toString(), ref.id().toString());
            require(version.status() == FlowVersionStatus.ADOPTED);
            return Runtime.valueOf(version.runtime());
        }
        ref.require("function-versions");
        FunctionVersionResponse version = versions.getFunctionVersion(ref.parentId().toString(), ref.id().toString());
        require(version.status() == FunctionVersionStatus.READY);
        return Runtime.valueOf(version.runtime());
    }

    private void validateSteps(Runtime runtime, List<StepInput> steps, Set<UUID> visiting) {
        require(steps != null && !steps.isEmpty() && steps.size() <= 100);
        Set<String> seen = new HashSet<>();
        for (StepInput step : steps) {
            require(step != null && step.key() != null && step.key().matches("[A-Za-z0-9_.-]{1,150}") && seen.add(step.key()));
            if (step.metadata() != null) validateJson(step.metadata());
            McpReference component = McpReference.parse(step.reference());
            require(componentRuntime(component, step.type()) == runtime);
            if (step.type() == FlowStepComponentType.SUB_FLOW) validateExecutableFlow(component, visiting, true);
        }
        FlowStepComponentType last = steps.getLast().type();
        if (runtime == Runtime.STATIC) require(steps.size() == 1 && last == FlowStepComponentType.FUNCTION);
        else {
            require(last == FlowStepComponentType.RESPONSE || last == FlowStepComponentType.SUB_FLOW);
            for (int i = 0; i < steps.size() - 1; i++) require(steps.get(i).type() != FlowStepComponentType.RESPONSE);
        }
    }

    private void validateExecutableFlow(McpReference ref, Set<UUID> visiting, boolean adopted) {
        require(visiting.size() < 20 && visiting.add(ref.id()));
        try {
            FlowVersionResponse version = flowVersions.getFlowVersion(ref.parentId().toString(), ref.id().toString());
            require(version.status() == FlowVersionStatus.DRAFT || version.status() == FlowVersionStatus.ADOPTED);
            if (adopted) require(version.status() == FlowVersionStatus.ADOPTED);
            require(Runtime.valueOf(version.runtime()) == Runtime.NODE);
            validateSteps(Runtime.NODE, toSteps(version), visiting);
        } finally { visiting.remove(ref.id()); }
    }

    private static List<StepInput> toSteps(FlowVersionResponse version) {
        require(version.steps() != null);
        int previous = 0;
        List<StepInput> result = new ArrayList<>();
        for (FlowStepResponse step : version.steps()) {
            require(step.position() > previous);
            previous = step.position();
            result.add(new StepInput(step.stepKey(), step.componentType(), McpReference.version(
                    step.componentType() == FlowStepComponentType.SUB_FLOW ? "flow-versions" : "function-versions",
                    step.componentId(), step.componentVersionId()), step.metadata()));
        }
        return result;
    }

    private UUID validateRoute(RouteInput route) {
        require(route != null && route.httpMethod() != null && route.httpMethod().matches("GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|CONNECT|TRACE"));
        String path = route.path();
        require(path != null && path.startsWith("/") && path.length() <= 2048 && !path.chars().anyMatch(Character::isISOControl));
        if (path.contains("*")) require(path.endsWith("/*") && path.indexOf('*') == path.length() - 1 && !path.contains(":"));
        Set<String> names = new HashSet<>();
        for (String segment : path.split("/")) if (segment.startsWith(":")) {
            require(segment.substring(1).matches("[A-Za-z_][A-Za-z0-9_]*") && names.add(segment.substring(1)));
        }
        McpReference gateway = McpReference.parse(route.gatewayRef()).require("gateways");
        gateways.getGateway(gateway.id().toString());
        return gateway.id();
    }

    private static void validateFiles(BuildRequest r) {
        require(r.files() != null && !r.files().isEmpty());
        require(r.files().size() <= 10000);
        Set<String> paths = new HashSet<>();
        long bytes = 0;
        for (var file : r.files()) {
            require(file != null && file.path() != null && file.content() != null);
            String path = file.path();
            require(!path.isBlank() && !path.startsWith("/") && !path.contains("\\") && !path.matches("^[A-Za-z]:.*") && path.indexOf('\0') < 0);
            for (String segment : path.split("/", -1)) require(!segment.isEmpty() && !segment.equals(".") && !segment.equals(".."));
            require(paths.add(path));
            bytes += file.content().getBytes(StandardCharsets.UTF_8).length + path.getBytes(StandardCharsets.UTF_8).length;
            require(bytes <= 7L * 1024 * 1024);
        }
        require(r.entrypoint() != null && paths.contains(r.entrypoint()));
    }

    private static void validateConfig(Map<String, String> values) {
        if (values != null) for (var entry : values.entrySet()) require(entry.getKey() != null
                && entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]{0,254}") && entry.getValue() != null);
    }
    private static Set<String> keys(Map<String, String> map) { return map == null ? Set.of() : map.keySet(); }
    private static List<McpReference> databaseRefs(List<String> refs) {
        if (refs == null) return List.of();
        List<McpReference> result = refs.stream().map(r -> McpReference.parse(r).require("databases")).toList();
        require(new HashSet<>(result).size() == result.size());
        return result;
    }
    private void validateJson(String value) {
        try { require(!value.isBlank() && json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value) != null); }
        catch (java.io.IOException exception) { throw new IllegalArgumentException("Invalid JSON"); }
    }
    private static void identity(String key, String name) {
        require(key != null && key.matches("[A-Za-z0-9_.-]{1,150}") && name != null && !name.isBlank() && name.length() <= 255);
    }
    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid workflow request"); }
}
