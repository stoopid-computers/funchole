package com.funchole.backend.controlplane.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.funchole.backend.controlplane.dto.FunctionVersionConfigResponse;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.entity.SourceFile;
import com.funchole.backend.controlplane.mapper.FunctionVersionMapper;
import com.funchole.backend.controlplane.service.DatabaseService;
import com.funchole.backend.controlplane.service.FunctionService;
import com.funchole.backend.controlplane.service.FunctionVersionCloneService;
import com.funchole.backend.controlplane.service.FunctionVersionConfigService;
import com.funchole.backend.controlplane.service.FunctionVersionDatabaseService;
import com.funchole.backend.controlplane.service.FunctionVersionDeploymentService;
import com.funchole.backend.controlplane.service.FunctionVersionService;
import com.funchole.backend.controlplane.service.FunctionVersionSourceService;
import com.funchole.backend.controlplane.service.ProfileService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BuildFunctionUseCaseTests {
    private final ProfileService profiles = mock(ProfileService.class);
    private final FunctionService functions = mock(FunctionService.class);
    private final FunctionVersionService versions = mock(FunctionVersionService.class);
    private final FunctionVersionCloneService clones = mock(FunctionVersionCloneService.class);
    private final FunctionVersionSourceService sources = mock(FunctionVersionSourceService.class);
    private final FunctionVersionConfigService configs = mock(FunctionVersionConfigService.class);
    private final FunctionVersionDatabaseService attachments = mock(FunctionVersionDatabaseService.class);
    private final FunctionVersionDeploymentService deployment = mock(FunctionVersionDeploymentService.class);
    private final DatabaseService databases = mock(DatabaseService.class);
    private final FunctionVersionMapper mapper = mock(FunctionVersionMapper.class);
    private final BuildFunctionUseCase useCase = new BuildFunctionUseCase(profiles, functions, versions, clones,
            sources, configs, attachments, deployment, databases, mapper);
    private final UUID userId = UUID.randomUUID();
    private final UUID functionId = UUID.randomUUID();
    private final UUID baseId = UUID.randomUUID();
    private final UUID draftId = UUID.randomUUID();

    private BuildFunctionUseCase.Command command(String path, Map<String, String> env, Map<String, String> secrets,
            List<UUID> add) {
        return new BuildFunctionUseCase.Command(functionId, baseId, null, null, null, path, "handler",
                List.of(new SourceFile(path, "export function handler() {}")), env, secrets, add, null);
    }

    @Test
    void rejectsMalformedSourceAndCollidingKeysBeforeOwnershipReads() {
        assertThatThrownBy(() -> useCase.execute(userId, command("../index.mjs", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.execute(userId, command("index.mjs", Map.of("TOKEN", "plain"),
                Map.of("TOKEN", "secret"), null))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(functions, versions, clones, sources, configs, databases);
    }

    @Test
    void exactFailedBaseIsClonedAndCompleteSourceReplacesItsFiles() throws Exception {
        FunctionVersion base = mock(FunctionVersion.class);
        when(base.getRuntime()).thenReturn("NODE");
        FunctionVersion draft = mock(FunctionVersion.class);
        when(draft.getId()).thenReturn(draftId);
        when(versions.getVersionById(userId, functionId, baseId)).thenReturn(base);
        when(configs.getConfig(userId, functionId, baseId)).thenReturn(new FunctionVersionConfigResponse(baseId, List.of(), List.of()));
        when(clones.createDraftVersion(eq(userId), eq(functionId), any())).thenReturn(draft);
        when(deployment.deploy(draftId)).thenReturn(draft);
        UUID databaseId = UUID.randomUUID();
        var result = useCase.execute(userId, command("index.mjs", Map.of("MODE", "test"), null, List.of(databaseId)));
        assertThat(result.partialFailure()).isFalse();
        assertThat(result.versionId()).isEqualTo(draftId);
        var create = org.mockito.ArgumentCaptor.forClass(com.funchole.backend.controlplane.dto.FunctionVersionCreateRequest.class);
        verify(clones).createDraftVersion(eq(userId), eq(functionId), create.capture());
        assertThat(create.getValue().cloneFromVersionId()).isEqualTo(baseId);
        assertThat(create.getValue().startEmpty()).isFalse();
        var order = inOrder(databases, clones, sources, configs, attachments, deployment);
        order.verify(databases).getDatabaseById(userId, databaseId);
        order.verify(clones).createDraftVersion(eq(userId), eq(functionId), any());
        order.verify(sources).submitSource(eq(draftId), argThat(bundle -> bundle.files().size() == 1
                && bundle.files().getFirst().relativePath().equals("index.mjs")));
        order.verify(configs).upsertEnvVar(userId, functionId, draftId, "MODE", "test");
        order.verify(attachments).attachDatabase(userId, functionId, draftId, databaseId);
        order.verify(deployment).deploy(draftId);
    }

    @Test
    void failureAfterDraftLeavesItsIdForSafeRetry() throws Exception {
        FunctionVersion base = mock(FunctionVersion.class);
        when(base.getRuntime()).thenReturn("NODE");
        FunctionVersion draft = mock(FunctionVersion.class);
        when(draft.getId()).thenReturn(draftId);
        when(versions.getVersionById(userId, functionId, baseId)).thenReturn(base);
        when(configs.getConfig(userId, functionId, baseId)).thenReturn(new FunctionVersionConfigResponse(baseId, List.of(), List.of()));
        when(clones.createDraftVersion(eq(userId), eq(functionId), any())).thenReturn(draft);
        when(configs.upsertSecret(userId, functionId, draftId, "TOKEN", "secret-value"))
                .thenThrow(new IllegalStateException("secret-value"));
        var result = useCase.execute(userId, command("index.mjs", null, Map.of("TOKEN", "secret-value"), null));
        assertThat(result.partialFailure()).isTrue();
        assertThat(result.functionId()).isEqualTo(functionId);
        assertThat(result.versionId()).isEqualTo(draftId);
        verifyNoInteractions(deployment);
    }
}
