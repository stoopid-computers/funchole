package com.funchole.backend.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.funchole.backend.controlplane.entity.EnvironmentProfile;
import com.funchole.backend.controlplane.entity.EnvironmentProfileEnvVar;
import com.funchole.backend.controlplane.entity.EnvironmentProfileSecret;
import com.funchole.backend.controlplane.repository.EnvironmentProfileEnvVarRepository;
import com.funchole.backend.controlplane.repository.EnvironmentProfileRepository;
import com.funchole.backend.controlplane.repository.EnvironmentProfileSecretRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvironmentProfileServiceDeleteTests {

    private final EnvironmentProfileRepository profiles = mock(EnvironmentProfileRepository.class);
    private final EnvironmentProfileEnvVarRepository envVars = mock(EnvironmentProfileEnvVarRepository.class);
    private final EnvironmentProfileSecretRepository secrets = mock(EnvironmentProfileSecretRepository.class);
    private final EnvironmentProfileService service =
            new EnvironmentProfileService(profiles, envVars, secrets, mock(FunctionSecretStore.class));

    private final UUID user = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    private void ownedProfile() {
        EnvironmentProfile profile = mock(EnvironmentProfile.class);
        when(profile.getId()).thenReturn(profileId);
        when(profiles.findByIdAndAppUser_IdAndDeletedAtIsNull(profileId, user)).thenReturn(Optional.of(profile));
    }

    @Test
    void deletesOnlyTheNamedKey() {
        ownedProfile();
        EnvironmentProfileEnvVar envVar = mock(EnvironmentProfileEnvVar.class);
        EnvironmentProfileSecret secret = mock(EnvironmentProfileSecret.class);
        when(envVars.findByEnvironmentProfile_IdAndKey(profileId, "API_URL")).thenReturn(Optional.of(envVar));
        when(secrets.findByEnvironmentProfile_IdAndKey(profileId, "STRIPE_KEY")).thenReturn(Optional.of(secret));

        assertThat(service.deleteEnvVar(user, profileId, "API_URL").environmentProfileId()).isEqualTo(profileId);
        service.deleteSecret(user, profileId, "STRIPE_KEY");

        verify(envVars).delete(envVar);
        verify(secrets).delete(secret);
    }

    @Test
    void missingKeyIsNotFoundAndDeletesNothing() {
        ownedProfile();
        when(envVars.findByEnvironmentProfile_IdAndKey(profileId, "NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteEnvVar(user, profileId, "NOPE")).isInstanceOf(ResourceNotFoundException.class);
        verify(envVars, never()).delete(any());
    }

    @Test
    void anotherUsersEnvironmentIsNotFound() {
        when(profiles.findByIdAndAppUser_IdAndDeletedAtIsNull(profileId, user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteSecret(user, profileId, "STRIPE_KEY")).isInstanceOf(ResourceNotFoundException.class);
        verify(secrets, never()).delete(any());
    }
}
