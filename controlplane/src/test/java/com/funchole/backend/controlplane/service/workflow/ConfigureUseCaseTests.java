package com.funchole.backend.controlplane.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.funchole.backend.controlplane.entity.EnvironmentProfile;
import com.funchole.backend.controlplane.service.DatabaseService;
import com.funchole.backend.controlplane.service.EnvironmentProfileService;
import com.funchole.backend.controlplane.service.FlowConfigurationService;
import com.funchole.backend.controlplane.service.FlowService;
import com.funchole.backend.controlplane.service.ProfileService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigureUseCaseTests {
    private final ProfileService users = mock(ProfileService.class);
    private final EnvironmentProfileService profiles = mock(EnvironmentProfileService.class);
    private final FlowConfigurationService bindings = mock(FlowConfigurationService.class);
    private final FlowService flows = mock(FlowService.class);
    private final DatabaseService databases = mock(DatabaseService.class);
    private final ConfigureUseCase useCase = new ConfigureUseCase(users, profiles, bindings, flows, databases);
    private final UUID userId = UUID.randomUUID();
    private final UUID flowId = UUID.randomUUID();
    private final UUID environmentId = UUID.randomUUID();

    @Test
    void rejectsCollidingEnvAndSecretNamesBeforeCreation() {
        var command = new ConfigureUseCase.Command(null, "prod", "Production", null,
                Map.of("TOKEN", "plain"), Map.of("TOKEN", "secret"), null, null, null, null, null, false);
        assertThatThrownBy(() -> useCase.execute(userId, command)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(profiles, bindings, flows, databases);
    }

    @Test
    void bindingPatchRejectsOverlapBeforeAnyMutation() {
        var command = new ConfigureUseCase.Command(null, null, null, null, null, null, flowId,
                List.of(new ConfigureUseCase.EnvironmentBinding(environmentId, 10)), List.of(environmentId),
                null, null, true);
        assertThatThrownBy(() -> useCase.execute(userId, command)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(profiles, bindings, flows, databases);
    }

    @Test
    void preflightsOwnedResourcesAndReportsPartialBindingWrites() throws Exception {
        UUID databaseId = UUID.randomUUID();
        var command = new ConfigureUseCase.Command(null, null, null, null, null, null, flowId,
                List.of(new ConfigureUseCase.EnvironmentBinding(environmentId, 10)), null,
                List.of(databaseId), null, true);
        when(bindings.attachDatabase(userId, flowId, databaseId)).thenThrow(new IllegalStateException("private"));
        var result = useCase.execute(userId, command);
        assertThat(result.partialFailure()).isTrue();
        assertThat(result.resourceId()).isEqualTo(flowId);
        var order = inOrder(flows, profiles, databases, bindings);
        order.verify(flows).getFlowById(userId, flowId);
        order.verify(profiles).getProfileById(userId, environmentId);
        order.verify(databases).getDatabaseById(userId, databaseId);
        order.verify(bindings).attachEnvironment(userId, flowId, environmentId, 10);
        order.verify(bindings).attachDatabase(userId, flowId, databaseId);
    }

    @Test
    void omittedProfileFieldsDoNotTriggerWrites() throws Exception {
        EnvironmentProfile profile = mock(EnvironmentProfile.class);
        when(profiles.getConfig(userId, environmentId)).thenReturn(new com.funchole.backend.controlplane.dto.EnvironmentProfileConfigResponse(
                environmentId, List.of(), List.of()));
        var command = new ConfigureUseCase.Command(environmentId, null, null, null, null, null,
                null, null, null, null, null, true);
        var result = useCase.execute(userId, command);
        assertThat(result.partialFailure()).isFalse();
        verify(profiles, never()).updateProfile(any(), any(), any());
        verify(profiles, never()).upsertEnvVar(any(), any(), any(), any());
        verify(profiles, never()).upsertSecret(any(), any(), any(), any());
    }
}
