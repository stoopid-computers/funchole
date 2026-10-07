package com.funchole.backend.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.funchole.backend.controlplane.constant.FlowStepComponentType;
import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.dto.FlowVersionResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionResponse;
import com.funchole.backend.controlplane.service.workflow.BuildFunctionUseCase;
import com.funchole.backend.controlplane.service.workflow.ComposeFlowUseCase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.mcp.annotation.McpTool;

class McpWorkflowToolsTests {
    private final FunctionMcpTools functions = mock(FunctionMcpTools.class);
    private final FunctionVersionMcpTools versions = mock(FunctionVersionMcpTools.class);
    private final FlowMcpTools flows = mock(FlowMcpTools.class);
    private final FlowVersionMcpTools flowVersions = mock(FlowVersionMcpTools.class);
    private final DatabaseMcpTools databases = mock(DatabaseMcpTools.class);
    private final GatewayMcpTools gateways = mock(GatewayMcpTools.class);
    private final InvocationMcpTools invocations = mock(InvocationMcpTools.class);
    private final McpFlowPublication publication = mock(McpFlowPublication.class);
    private final BuildFunctionUseCase builds = mock(BuildFunctionUseCase.class);
    private final ComposeFlowUseCase compositions = mock(ComposeFlowUseCase.class);
    private final McpWorkflowTools tools = new McpWorkflowTools(functions, versions, flows, flowVersions,
            databases, gateways, invocations, publication, builds, compositions);
    private final UUID functionId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();
    private final UUID flowId = UUID.randomUUID();
    private final UUID draftId = UUID.randomUUID();

    @Test
    void buildMapsReferencesAndReturnsAsyncReceiptWithoutSecretValues() throws Exception {
        UUID databaseId = UUID.randomUUID();
        FunctionVersionResponse version = new FunctionVersionResponse(versionId, functionId, 1,
                FunctionVersionStatus.PUBLISHING, "NODE", null, null, null, null, null, null, null, null);
        when(builds.execute(any(), any())).thenReturn(new BuildFunctionUseCase.Result(functionId, versionId, version, false));
        var request = new McpWorkflowTools.BuildRequest(null, null, "hello", "Hello", McpWorkflowTools.Runtime.NODE,
                "index.mjs", "handler", List.of(new FunctionVersionMcpTools.SourceFileInput("index.mjs", "export function handler() {}")),
                Map.of("MODE", "test"), Map.of("TOKEN", "secret-value"), List.of(McpReference.of("databases", databaseId)), null);
        try (var current = mockStatic(CurrentMcpUser.class)) {
            current.when(CurrentMcpUser::id).thenReturn(UUID.randomUUID());
            var result = tools.buildFunction(request);
            assertThat(result.ok()).isTrue();
            assertThat(result.reference()).isEqualTo(McpReference.version("function-versions", functionId, versionId));
            assertThat(result.toString()).doesNotContain("secret-value");
        }
        ArgumentCaptor<BuildFunctionUseCase.Command> command = ArgumentCaptor.forClass(BuildFunctionUseCase.Command.class);
        verify(builds).execute(any(), command.capture());
        assertThat(command.getValue().addDatabases()).containsExactly(databaseId);
        assertThat(command.getValue().files().getFirst().relativePath()).isEqualTo("index.mjs");
    }

    @Test
    void partialBuildPointsToSurvivingDraft() throws Exception {
        when(builds.execute(any(), any())).thenReturn(new BuildFunctionUseCase.Result(functionId, versionId, null, true));
        var request = new McpWorkflowTools.BuildRequest(null, null, "hello", "Hello", McpWorkflowTools.Runtime.NODE,
                "index.mjs", null, List.of(new FunctionVersionMcpTools.SourceFileInput("index.mjs", "")), null, null, null, null);
        try (var current = mockStatic(CurrentMcpUser.class)) {
            current.when(CurrentMcpUser::id).thenReturn(UUID.randomUUID());
            var result = tools.buildFunction(request);
            assertThat(result.code()).isEqualTo("PARTIAL_FAILURE");
            assertThat(result.reference()).isEqualTo(McpReference.version("function-versions", functionId, versionId));
        }
    }

    @Test
    void composeMapsOrderedPinnedStepsToUseCase() throws Exception {
        String component = McpReference.version("function-versions", functionId, versionId);
        when(versions.getFunctionVersion(functionId.toString(), versionId.toString())).thenReturn(new FunctionVersionResponse(
                versionId, functionId, 1, FunctionVersionStatus.READY, "NODE", null, null, null, null, null, null, null, null));
        when(compositions.execute(any(), any())).thenReturn(new ComposeFlowUseCase.Result(flowId, draftId));
        when(flowVersions.getFlowVersion(flowId.toString(), draftId.toString())).thenReturn(new FlowVersionResponse(
                draftId, flowId, 1, FlowVersionStatus.DRAFT, "NODE", null, List.of(), null, null, null, null));
        var request = new McpWorkflowTools.ComposeRequest(McpReference.of("flows", flowId), null, null,
                null, null, null, null, null, component, null);
        try (var current = mockStatic(CurrentMcpUser.class)) {
            current.when(CurrentMcpUser::id).thenReturn(UUID.randomUUID());
            assertThat(tools.composeFlow(request).ok()).isTrue();
        }
        ArgumentCaptor<ComposeFlowUseCase.Command> command = ArgumentCaptor.forClass(ComposeFlowUseCase.Command.class);
        verify(compositions).execute(any(), command.capture());
        assertThat(command.getValue().steps()).containsExactly(new ComposeFlowUseCase.Step(
                "main", FlowStepComponentType.RESPONSE, functionId, versionId, null));
    }

    @Test
    void literalNoneExpectationIsRejected() {
        var request = new McpWorkflowTools.PublishRequest(McpReference.version("flow-versions", flowId, draftId), "none", null);
        assertThat(tools.publishFlow(request).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(publication);
    }

    @Test
    void nullableExpectationIsPassedAsNoActiveRevision() {
        when(flowVersions.getFlowVersion(flowId.toString(), draftId.toString())).thenReturn(new FlowVersionResponse(
                draftId, flowId, 1, FlowVersionStatus.DRAFT, "NODE", null, List.of(), null, null, null, null));
        var request = new McpWorkflowTools.PublishRequest(McpReference.version("flow-versions", flowId, draftId), null, null);
        assertThat(tools.publishFlow(request).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(publication);
    }

    @Test
    void declaresFourCuratedTools() {
        assertThat(java.util.Arrays.stream(McpWorkflowTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(McpTool.class)).map(m -> m.getAnnotation(McpTool.class).name()))
                .containsExactlyInAnyOrder("build_function", "compose_flow", "invoke", "publish_flow");
    }
}
