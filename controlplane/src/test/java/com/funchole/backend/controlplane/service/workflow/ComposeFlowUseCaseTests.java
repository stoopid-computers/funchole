package com.funchole.backend.controlplane.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.funchole.backend.controlplane.constant.FlowStepComponentType;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.dto.FlowStepCreateRequest;
import com.funchole.backend.controlplane.entity.FlowVersion;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.service.FlowService;
import com.funchole.backend.controlplane.service.FlowStepService;
import com.funchole.backend.controlplane.service.FlowVersionService;
import com.funchole.backend.controlplane.service.FunctionVersionService;
import com.funchole.backend.controlplane.service.GatewayService;
import com.funchole.backend.controlplane.service.ProfileService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ComposeFlowUseCaseTests {
    private final ProfileService profiles = mock(ProfileService.class);
    private final FlowService flows = mock(FlowService.class);
    private final FlowVersionService versions = mock(FlowVersionService.class);
    private final FlowStepService steps = mock(FlowStepService.class);
    private final FunctionVersionService functions = mock(FunctionVersionService.class);
    private final GatewayService gateways = mock(GatewayService.class);
    private final ComposeFlowUseCase useCase = new ComposeFlowUseCase(profiles, flows, versions, steps, functions, gateways);
    private final UUID userId = UUID.randomUUID();
    private final UUID flowId = UUID.randomUUID();
    private final UUID draftId = UUID.randomUUID();
    private final UUID functionId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    private ComposeFlowUseCase.Command command(List<ComposeFlowUseCase.Step> entries) {
        return new ComposeFlowUseCase.Command(flowId, null, null, null, null, null, null, "NODE", entries);
    }
    private ComposeFlowUseCase.Step step(String key, FlowStepComponentType type) {
        return new ComposeFlowUseCase.Step(key, type, functionId, versionId, null);
    }

    @Test
    void rejectsNonterminalCompositionBeforeDraft() {
        FunctionVersion function = mock(FunctionVersion.class);
        when(function.getStatus()).thenReturn(FunctionVersionStatus.READY);
        when(function.getRuntime()).thenReturn("NODE");
        when(functions.getVersionById(userId, functionId, versionId)).thenReturn(function);
        assertThatThrownBy(() -> useCase.execute(userId, command(List.of(step("main", FlowStepComponentType.FUNCTION)))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(versions, never()).createDraftVersion(any(), any(), any());
    }

    @Test
    void draftsPinnedComponentsInCallerOrder() throws Exception {
        FunctionVersion function = mock(FunctionVersion.class);
        when(function.getStatus()).thenReturn(FunctionVersionStatus.READY);
        when(function.getRuntime()).thenReturn("NODE");
        when(functions.getVersionById(userId, functionId, versionId)).thenReturn(function);
        FlowVersion draft = mock(FlowVersion.class);
        when(draft.getId()).thenReturn(draftId);
        when(versions.createDraftVersion(eq(userId), eq(flowId), any())).thenReturn(draft);
        var result = useCase.execute(userId, command(List.of(step("first", FlowStepComponentType.FUNCTION),
                step("last", FlowStepComponentType.RESPONSE))));
        assertThat(result.versionId()).isEqualTo(draftId);
        ArgumentCaptor<FlowStepCreateRequest> captures = ArgumentCaptor.forClass(FlowStepCreateRequest.class);
        verify(steps, times(2)).createStep(eq(userId), eq(flowId), eq(draftId), captures.capture());
        assertThat(captures.getAllValues()).extracting(FlowStepCreateRequest::position).containsExactly(1, 2);
        assertThat(captures.getAllValues()).extracting(FlowStepCreateRequest::componentType)
                .containsExactly(FlowStepComponentType.FUNCTION, FlowStepComponentType.RESPONSE);
    }
}
