package com.funchole.backend.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.funchole.backend.controlplane.constant.FlowStepComponentType;
import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.dto.*;
import com.funchole.backend.controlplane.entity.Flow;
import com.funchole.backend.controlplane.service.FlowService;
import com.funchole.backend.controlplane.service.FlowVersionService;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.transaction.annotation.Transactional;

class McpWorkflowToolsTests {
    private final FunctionMcpTools functions = mock(FunctionMcpTools.class);
    private final FunctionVersionMcpTools versions = mock(FunctionVersionMcpTools.class);
    private final FlowMcpTools flows = mock(FlowMcpTools.class);
    private final FlowVersionMcpTools flowVersions = mock(FlowVersionMcpTools.class);
    private final DatabaseMcpTools databases = mock(DatabaseMcpTools.class);
    private final GatewayMcpTools gateways = mock(GatewayMcpTools.class);
    private final InvocationMcpTools invocations = mock(InvocationMcpTools.class);
    private final McpFlowPublication publication = mock(McpFlowPublication.class);
    private final McpWorkflowTools tools = new McpWorkflowTools(functions, versions, flows, flowVersions,
            databases, gateways, invocations, publication);
    private final UUID fid = UUID.randomUUID();
    private final UUID vid = UUID.randomUUID();
    private final UUID baseId = UUID.randomUUID();
    private final UUID flowId = UUID.randomUUID();
    private final UUID flowVersionId = UUID.randomUUID();

    private FunctionVersionResponse functionVersion(UUID id, FunctionVersionStatus status, String runtime) {
        return new FunctionVersionResponse(id, fid, 1, status, runtime, null, null, null, null, null, null, null, null);
    }
    private FlowVersionResponse flowVersion(FlowVersionStatus status) {
        return new FlowVersionResponse(flowVersionId, flowId, 1, status, "NODE", null, List.of(), null, null, null, null);
    }
    private McpWorkflowTools.BuildRequest build(String function, String base, String key, String name,
                                               Map<String, String> env, Map<String, String> secrets, List<String> dbs) {
        return new McpWorkflowTools.BuildRequest(function, base, key, name, McpWorkflowTools.Runtime.NODE,
                "index.mjs", "handler", List.of(new FunctionVersionMcpTools.SourceFileInput("index.mjs", "export function handler() {}")),
                env, secrets, dbs, null);
    }
    private void stubNewBuild() throws Exception {
        when(functions.createFunction("hello", "Hello", null, "NODE"))
                .thenReturn(new FunctionResponse(fid, "hello", "Hello", null, "NODE", null, null));
        when(versions.createFunctionVersion(fid.toString(), "NODE", null, null, true))
                .thenReturn(functionVersion(vid, FunctionVersionStatus.DRAFT, "NODE"));
        when(versions.deployFunctionVersion(fid.toString(), vid.toString()))
                .thenReturn(functionVersion(vid, FunctionVersionStatus.PUBLISHING, "NODE"));
    }

    @Test
    void newBuildValidatesOwnershipThenCreatesSourceConfigBindingsAndDeploys() throws Exception {
        stubNewBuild();
        UUID db = UUID.randomUUID();
        var result = tools.buildFunction(build(null, null, "hello", "Hello", Map.of("MODE", "test"),
                Map.of("TOKEN", "do-not-return"), List.of(McpReference.of("databases", db))));
        assertThat(result.ok()).isTrue();
        assertThat(result.reference()).isEqualTo(McpReference.version("function-versions", fid, vid));
        assertThat(result.data()).isInstanceOf(FunctionVersionResponse.class);
        assertThat(result.toString()).doesNotContain("do-not-return");
        var order = inOrder(databases, functions, versions);
        order.verify(databases).getDatabase(db.toString());
        order.verify(functions).createFunction("hello", "Hello", null, "NODE");
        order.verify(versions).createFunctionVersion(fid.toString(), "NODE", null, null, true);
        order.verify(versions).submitFunctionVersionSource(eq(fid.toString()), eq(vid.toString()), anyList(), eq("index.mjs"), eq("handler"));
        order.verify(versions).setFunctionVersionEnvVar(fid.toString(), vid.toString(), "MODE", "test");
        order.verify(versions).setFunctionVersionSecret(fid.toString(), vid.toString(), "TOKEN", "do-not-return");
        order.verify(versions).attachFunctionVersionDatabase(fid.toString(), vid.toString(), db.toString());
        order.verify(versions).deployFunctionVersion(fid.toString(), vid.toString());
    }

    @Test
    void failedExplicitBaseIsClonedAndOmittedConfigurationIsNotOverwritten() {
        String functionRef = McpReference.of("functions", fid);
        String baseRef = McpReference.version("function-versions", fid, baseId);
        when(versions.getFunctionVersion(fid.toString(), baseId.toString()))
                .thenReturn(functionVersion(baseId, FunctionVersionStatus.FAILED, "NODE"));
        when(versions.getFunctionVersionConfig(fid.toString(), baseId.toString()))
                .thenReturn(new FunctionVersionConfigResponse(baseId, List.of(), List.of()));
        when(versions.createFunctionVersion(fid.toString(), "NODE", null, baseId.toString(), false))
                .thenReturn(functionVersion(vid, FunctionVersionStatus.DRAFT, "NODE"));
        when(versions.deployFunctionVersion(fid.toString(), vid.toString()))
                .thenReturn(functionVersion(vid, FunctionVersionStatus.PUBLISHING, "NODE"));
        assertThat(tools.buildFunction(build(functionRef, baseRef, null, null, null, null, null)).ok()).isTrue();
        verify(versions).createFunctionVersion(fid.toString(), "NODE", null, baseId.toString(), false);
        verify(versions, never()).setFunctionVersionEnvVar(anyString(), anyString(), anyString(), anyString());
        verify(versions, never()).setFunctionVersionSecret(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(databases);
    }

    @Test
    void missingBaseAndMalformedSourceRejectBeforeMutations() {
        assertThat(tools.buildFunction(build(McpReference.of("functions", fid), null, null, null, null, null, null)).code())
                .isEqualTo("INVALID_INPUT");
        var invalid = new McpWorkflowTools.BuildRequest(null, null, "hello", "Hello", McpWorkflowTools.Runtime.NODE,
                "../index.mjs", null, List.of(new FunctionVersionMcpTools.SourceFileInput("../index.mjs", "")), null, null, null, null);
        assertThat(tools.buildFunction(invalid).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(functions, versions, databases);
    }

    @Test
    void oversizedSourceAndBaseFromAnotherFunctionRejectBeforeMutations() {
        var oversized = new McpWorkflowTools.BuildRequest(null, null, "hello", "Hello", McpWorkflowTools.Runtime.NODE,
                "a", null, List.of(new FunctionVersionMcpTools.SourceFileInput("a", "x".repeat(7 * 1024 * 1024 + 1))),
                null, null, null, null);
        assertThat(tools.buildFunction(oversized).code()).isEqualTo("INVALID_INPUT");
        var wrongBase = build(McpReference.of("functions", fid),
                McpReference.version("function-versions", UUID.randomUUID(), baseId), null, null, null, null, null);
        assertThat(tools.buildFunction(wrongBase).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(functions, versions, databases);
    }

    @Test
    void duplicateFilesConfigKeyAndDatabaseOwnershipFailWithoutCreatingState() throws Exception {
        var duplicate = new McpWorkflowTools.BuildRequest(null, null, "hello", "Hello", McpWorkflowTools.Runtime.NODE,
                "a", null, List.of(new FunctionVersionMcpTools.SourceFileInput("a", ""), new FunctionVersionMcpTools.SourceFileInput("a", "")),
                null, null, null, null);
        assertThat(tools.buildFunction(duplicate).code()).isEqualTo("INVALID_INPUT");
        assertThat(tools.buildFunction(build(null, null, "hello", "Hello", Map.of("BAD-KEY", "value"), null, null)).code()).isEqualTo("INVALID_INPUT");
        UUID db = UUID.randomUUID();
        when(databases.getDatabase(db.toString())).thenThrow(new ResourceNotFoundException("private details"));
        var denied = tools.buildFunction(build(null, null, "hello", "Hello", null, null, List.of(McpReference.of("databases", db))));
        assertThat(denied.code()).isEqualTo("NOT_FOUND");
        assertThat(denied.message()).doesNotContain("private details");
        verify(functions, never()).createFunction(any(), any(), any(), any());
        verifyNoInteractions(versions);
    }

    @Test
    void errorAfterDraftCreationReturnsSurvivingDraftAndNoSecret() throws Exception {
        stubNewBuild();
        when(versions.setFunctionVersionSecret(fid.toString(), vid.toString(), "TOKEN", "private-value"))
                .thenThrow(new IllegalStateException("private-value"));
        var result = tools.buildFunction(build(null, null, "hello", "Hello", null, Map.of("TOKEN", "private-value"), null));
        assertThat(result.code()).isEqualTo("PARTIAL_FAILURE");
        assertThat(result.reference()).isEqualTo(McpReference.version("function-versions", fid, vid));
        assertThat(result.toString()).doesNotContain("private-value");
        verify(versions, never()).deployFunctionVersion(anyString(), anyString());
    }

    @Test
    void shorthandUsesNodeResponseAndLeavesExistingRouteAlone() throws Exception {
        shorthand("NODE", FlowStepComponentType.RESPONSE);
    }

    @Test
    void shorthandUsesStaticFunctionAndLeavesExistingRouteAlone() throws Exception {
        shorthand("STATIC", FlowStepComponentType.FUNCTION);
    }

    private void shorthand(String runtime, FlowStepComponentType expected) throws Exception {
        String component = McpReference.version("function-versions", fid, vid);
        when(versions.getFunctionVersion(fid.toString(), vid.toString())).thenReturn(functionVersion(vid, FunctionVersionStatus.READY, runtime));
        when(publication.compose(any(), eq(flowId), isNull(), eq(runtime), anyList())).thenReturn(flowVersion(FlowVersionStatus.DRAFT));
        var request = new McpWorkflowTools.ComposeRequest(McpReference.of("flows", flowId), null, null, null, null, null, null, null, component, null);
        assertThat(tools.composeFlow(request).ok()).isTrue();
        @SuppressWarnings("unchecked") ArgumentCaptor<List<McpWorkflowTools.StepInput>> steps = ArgumentCaptor.forClass(List.class);
        verify(publication).compose(eq(request), eq(flowId), isNull(), eq(runtime), steps.capture());
        assertThat(steps.getValue().getFirst().type()).isEqualTo(expected);
        verify(flows, never()).updateFlow(any(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(gateways);
    }

    @Test
    void invalidTerminalAndExistingRouteFieldsRejectComposition() {
        String component = McpReference.version("function-versions", fid, vid);
        when(versions.getFunctionVersion(fid.toString(), vid.toString())).thenReturn(functionVersion(vid, FunctionVersionStatus.READY, "NODE"));
        var invalid = new McpWorkflowTools.ComposeRequest(McpReference.of("flows", flowId), null, null, null, null, null, null,
                McpWorkflowTools.Runtime.NODE, null, List.of(new McpWorkflowTools.StepInput("main", FlowStepComponentType.FUNCTION, component, null)));
        assertThat(tools.composeFlow(invalid).code()).isEqualTo("INVALID_INPUT");
        var routeEdit = new McpWorkflowTools.ComposeRequest(McpReference.of("flows", flowId), null, null, null, "GET", "/new", null,
                null, component, null);
        assertThat(tools.composeFlow(routeEdit).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(publication);
    }

    @Test
    void componentOwnershipFailureLeavesNoDraft() {
        String component = McpReference.version("function-versions", fid, vid);
        when(versions.getFunctionVersion(fid.toString(), vid.toString())).thenThrow(new ResourceNotFoundException("not owned"));
        var request = new McpWorkflowTools.ComposeRequest(McpReference.of("flows", flowId), null, null, null, null, null, null,
                null, component, null);
        assertThat(tools.composeFlow(request).code()).isEqualTo("NOT_FOUND");
        verifyNoInteractions(publication);
    }

    @Test
    void invokeRejectsStaticAndMissingTerminalBeforeExecution() {
        String component = McpReference.version("function-versions", fid, vid);
        when(versions.getFunctionVersion(fid.toString(), vid.toString())).thenReturn(functionVersion(vid, FunctionVersionStatus.READY, "STATIC"));
        assertThat(tools.invoke(component, "{}").code()).isEqualTo("INVALID_INPUT");
        when(flowVersions.getFlowVersion(flowId.toString(), flowVersionId.toString())).thenReturn(flowVersion(FlowVersionStatus.DRAFT));
        assertThat(tools.invoke(McpReference.version("flow-versions", flowId, flowVersionId), "{}").code()).isEqualTo("INVALID_INPUT");
        assertThat(tools.invoke(component, "{} {}").code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(invocations);
    }

    @Test
    void missingPublicationExpectationRejectsWithoutMutation() {
        var request = new McpWorkflowTools.PublishRequest(McpReference.version("flow-versions", flowId, flowVersionId), null, null);
        assertThat(tools.publishFlow(request).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(publication, flowVersions);
    }

    @Test
    void expectationFromAnotherParentIsRejected() {
        var request = new McpWorkflowTools.PublishRequest(McpReference.version("flow-versions", flowId, flowVersionId),
                McpReference.version("flow-versions", UUID.randomUUID(), baseId), null);
        assertThat(tools.publishFlow(request).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(publication, flowVersions);
    }

    @Test
    void publicationLocksBeforeActiveComparisonAndConflictsWithoutWrites() {
        FlowService service = mock(FlowService.class);
        FlowVersionService versionService = mock(FlowVersionService.class);
        EntityManager em = mock(EntityManager.class);
        Flow flow = mock(Flow.class);
        UUID user = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        when(service.getFlowById(user, flowId)).thenReturn(flow);
        when(flow.getActiveFlowVersionId()).thenReturn(active);
        var local = new McpFlowPublication(service, versionService, flows, flowVersions, em);
        try (var current = mockStatic(CurrentMcpUser.class)) {
            current.when(CurrentMcpUser::id).thenReturn(user);
            var result = local.publish(new McpReference("flow-versions", flowId, flowVersionId), null, null, null);
            assertThat(result.code()).isEqualTo("CONFLICT");
            var order = inOrder(service, em, flow);
            order.verify(service).getFlowById(user, flowId);
            order.verify(em).refresh(flow, LockModeType.PESSIMISTIC_WRITE);
            order.verify(service).getFlowById(user, flowId);
            order.verify(flow).getActiveFlowVersionId();
            verifyNoInteractions(versionService, flowVersions);
            verify(service, never()).updateFlow(any(), any(), any());
        }
    }

    @Test
    void publicationPreservesLabelsAndAdoptsBeforeExplicitRouteEdit() {
        FlowService service = mock(FlowService.class);
        FlowVersionService versionService = mock(FlowVersionService.class);
        EntityManager em = mock(EntityManager.class);
        Flow flow = mock(Flow.class);
        UUID user = UUID.randomUUID();
        UUID gateway = UUID.randomUUID();
        when(service.getFlowById(user, flowId)).thenReturn(flow);
        when(flow.getId()).thenReturn(flowId);
        when(flow.getName()).thenReturn("Preserved");
        when(flow.getDescription()).thenReturn("Description");
        when(flow.getPriority()).thenReturn(42);
        when(flowVersions.getFlowVersion(flowId.toString(), flowVersionId.toString())).thenReturn(flowVersion(FlowVersionStatus.ADOPTED));
        var local = new McpFlowPublication(service, versionService, flows, flowVersions, em);
        try (var current = mockStatic(CurrentMcpUser.class)) {
            current.when(CurrentMcpUser::id).thenReturn(user);
            var result = local.publish(new McpReference("flow-versions", flowId, flowVersionId), null,
                    new McpWorkflowTools.RouteInput(McpReference.of("gateways", gateway), "POST", "/changed", null), gateway);
            assertThat(result.ok()).isTrue();
            var order = inOrder(versionService, service);
            order.verify(versionService).adoptVersion(user, flowId, flowVersionId);
            order.verify(service).updateFlow(user, flowId, new FlowUpdateRequest("Preserved", "Description", gateway, "POST", "/changed", 42));
        }
    }

    @Test
    void compositionCreatesOnlyDraftAndPositiveOrderedSteps() throws Exception {
        FlowService service = mock(FlowService.class);
        FlowVersionService versionService = mock(FlowVersionService.class);
        EntityManager em = mock(EntityManager.class);
        var local = new McpFlowPublication(service, versionService, flows, flowVersions, em);
        when(flowVersions.createFlowVersion(flowId.toString(), "NODE", null)).thenReturn(flowVersion(FlowVersionStatus.DRAFT));
        String component = McpReference.version("function-versions", fid, vid);
        var steps = List.of(new McpWorkflowTools.StepInput("first", FlowStepComponentType.FUNCTION, component, null),
                new McpWorkflowTools.StepInput("last", FlowStepComponentType.RESPONSE, component, null));
        local.compose(null, flowId, null, "NODE", steps);
        verify(flowVersions).createFlowStep(flowId.toString(), flowVersionId.toString(), "first", "FUNCTION", 1, fid.toString(), vid.toString(), null);
        verify(flowVersions).createFlowStep(flowId.toString(), flowVersionId.toString(), "last", "RESPONSE", 2, fid.toString(), vid.toString(), null);
        verifyNoInteractions(service, versionService, flows, em);
    }

    @Test
    void onlyFourToolNamesAndTransactionalBoundariesAreDeclared() {
        assertThat(java.util.Arrays.stream(McpWorkflowTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpTool.class)).map(m -> m.getAnnotation(McpTool.class).name()))
                .containsExactlyInAnyOrder("build_function", "compose_flow", "invoke", "publish_flow");
        assertThat(java.util.Arrays.stream(McpFlowPublication.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Transactional.class)).map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("compose", "publish");
    }
}
