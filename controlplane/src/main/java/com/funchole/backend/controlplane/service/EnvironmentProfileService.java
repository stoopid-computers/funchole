package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.dto.EnvironmentProfileConfigResponse;
import com.funchole.backend.controlplane.dto.EnvironmentProfileCreateRequest;
import com.funchole.backend.controlplane.dto.EnvironmentProfileUpdateRequest;
import com.funchole.backend.controlplane.dto.FunctionVersionEnvVarResponse;
import com.funchole.backend.controlplane.dto.FunctionVersionSecretResponse;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.EnvironmentProfile;
import com.funchole.backend.controlplane.entity.EnvironmentProfileEnvVar;
import com.funchole.backend.controlplane.entity.EnvironmentProfileSecret;
import com.funchole.backend.controlplane.repository.EnvironmentProfileEnvVarRepository;
import com.funchole.backend.controlplane.repository.EnvironmentProfileRepository;
import com.funchole.backend.controlplane.repository.EnvironmentProfileSecretRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnvironmentProfileService {

    private static final Pattern CONFIG_KEY_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final EnvironmentProfileRepository profileRepository;
    private final EnvironmentProfileEnvVarRepository envVarRepository;
    private final EnvironmentProfileSecretRepository secretRepository;
    private final FunctionSecretStore functionSecretStore;

    public EnvironmentProfileService(
            EnvironmentProfileRepository profileRepository,
            EnvironmentProfileEnvVarRepository envVarRepository,
            EnvironmentProfileSecretRepository secretRepository,
            FunctionSecretStore functionSecretStore
    ) {
        this.profileRepository = profileRepository;
        this.envVarRepository = envVarRepository;
        this.secretRepository = secretRepository;
        this.functionSecretStore = functionSecretStore;
    }

    public Page<EnvironmentProfile> listProfiles(UUID appUserId, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page - 1, 0),
                Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, EnvironmentProfile::getCreatedAt)
        );
        return profileRepository.findAllByAppUser_IdAndDeletedAtIsNull(appUserId, pageable);
    }

    public EnvironmentProfile getProfileById(UUID appUserId, UUID profileId) {
        return profileRepository.findByIdAndAppUser_IdAndDeletedAtIsNull(profileId, appUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment not found: " + profileId));
    }

    @Transactional
    public EnvironmentProfile createProfile(AppUser appUser, EnvironmentProfileCreateRequest request) {
        if (profileRepository.existsByAppUser_IdAndEnvironmentKeyAndDeletedAtIsNull(appUser.getId(), request.environmentKey())) {
            throw new IllegalArgumentException("Environment key already in use: " + request.environmentKey());
        }
        return profileRepository.save(EnvironmentProfile.create(
                appUser,
                request.environmentKey(),
                request.name(),
                request.description()
        ));
    }

    @Transactional
    public EnvironmentProfile updateProfile(UUID appUserId, UUID profileId, EnvironmentProfileUpdateRequest request) {
        EnvironmentProfile profile = getProfileById(appUserId, profileId);
        profile.update(request.name(), request.description());
        return profileRepository.save(profile);
    }

    @Transactional
    public void deleteProfile(UUID appUserId, UUID profileId) {
        EnvironmentProfile profile = getProfileById(appUserId, profileId);
        profile.softDelete();
        profileRepository.save(profile);
    }

    @Transactional(readOnly = true)
    public EnvironmentProfileConfigResponse getConfig(UUID appUserId, UUID profileId) {
        EnvironmentProfile profile = getProfileById(appUserId, profileId);
        return toConfigResponse(profile);
    }

    @Transactional
    public EnvironmentProfileConfigResponse upsertEnvVar(UUID appUserId, UUID profileId, String key, String value) {
        validateKey(key);
        EnvironmentProfile profile = getProfileById(appUserId, profileId);
        if (secretRepository.findByEnvironmentProfile_IdAndKey(profileId, key).isPresent()) {
            throw new IllegalArgumentException("Config key already exists as a secret: " + key);
        }
        EnvironmentProfileEnvVar envVar = envVarRepository.findByEnvironmentProfile_IdAndKey(profileId, key)
                .map(existing -> {
                    existing.updateValue(value);
                    return existing;
                })
                .orElseGet(() -> EnvironmentProfileEnvVar.create(profile, key, value));
        envVarRepository.save(envVar);
        return toConfigResponse(profile);
    }

    @Transactional
    public EnvironmentProfileConfigResponse upsertSecret(UUID appUserId, UUID profileId, String key, String value) {
        validateKey(key);
        EnvironmentProfile profile = getProfileById(appUserId, profileId);
        if (envVarRepository.findByEnvironmentProfile_IdAndKey(profileId, key).isPresent()) {
            throw new IllegalArgumentException("Config key already exists as an env var: " + key);
        }
        String secretRef = functionSecretStore.saveForEnvironment(profileId, key, value);
        EnvironmentProfileSecret secret = secretRepository.findByEnvironmentProfile_IdAndKey(profileId, key)
                .map(existing -> {
                    existing.updateSecretRef(secretRef);
                    return existing;
                })
                .orElseGet(() -> EnvironmentProfileSecret.create(profile, key, secretRef));
        secretRepository.save(secret);
        return toConfigResponse(profile);
    }

    @Transactional
    public EnvironmentProfileConfigResponse deleteEnvVar(UUID appUserId, UUID profileId, String key) {
        EnvironmentProfile profile = getProfileById(appUserId, profileId);
        EnvironmentProfileEnvVar envVar = envVarRepository.findByEnvironmentProfile_IdAndKey(profileId, key)
                .orElseThrow(() -> new ResourceNotFoundException("Env var not found: " + key));
        envVarRepository.delete(envVar);
        return toConfigResponse(profile);
    }

    // ponytail: drops the key row so it is no longer injected; FunctionSecretStore has no delete, so the stored value lingers unreferenced.
    @Transactional
    public EnvironmentProfileConfigResponse deleteSecret(UUID appUserId, UUID profileId, String key) {
        EnvironmentProfile profile = getProfileById(appUserId, profileId);
        EnvironmentProfileSecret secret = secretRepository.findByEnvironmentProfile_IdAndKey(profileId, key)
                .orElseThrow(() -> new ResourceNotFoundException("Secret not found: " + key));
        secretRepository.delete(secret);
        return toConfigResponse(profile);
    }

    private EnvironmentProfileConfigResponse toConfigResponse(EnvironmentProfile profile) {
        UUID profileId = profile.getId();
        return new EnvironmentProfileConfigResponse(
                profileId,
                envVarRepository.findAllByEnvironmentProfile_IdOrderByKeyAsc(profileId)
                        .stream()
                        .map(envVar -> new FunctionVersionEnvVarResponse(
                                envVar.getId(),
                                envVar.getKey(),
                                envVar.getValue(),
                                envVar.getCreatedAt(),
                                envVar.getUpdatedAt()
                        ))
                        .toList(),
                secretRepository.findAllByEnvironmentProfile_IdOrderByKeyAsc(profileId)
                        .stream()
                        .map(secret -> new FunctionVersionSecretResponse(
                                secret.getId(),
                                secret.getKey(),
                                secret.getSecretRef(),
                                secret.getCreatedAt(),
                                secret.getUpdatedAt()
                        ))
                        .toList()
        );
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Config key is required");
        }
        if (key.length() > 255) {
            throw new IllegalArgumentException("Config key must be at most 255 characters");
        }
        if (!CONFIG_KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Config key may only contain letters, numbers and '_' and must start with a letter or '_'");
        }
    }
}
