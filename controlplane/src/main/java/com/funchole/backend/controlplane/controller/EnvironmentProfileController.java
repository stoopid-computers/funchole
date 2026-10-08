package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.dto.EnvironmentProfileConfigResponse;
import com.funchole.backend.controlplane.dto.EnvironmentProfileCreateRequest;
import com.funchole.backend.controlplane.dto.EnvironmentProfileResponse;
import com.funchole.backend.controlplane.dto.EnvironmentProfileUpdateRequest;
import com.funchole.backend.controlplane.dto.FunctionVersionEnvVarRequest;
import com.funchole.backend.controlplane.dto.FunctionVersionSecretRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.EnvironmentProfile;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.EnvironmentProfileService;
import com.funchole.backend.controlplane.service.ProfileService;
import com.funchole.backend.core.base.mapper.PaginationMapper;
import com.funchole.backend.core.base.response.ApiResponse;
import com.funchole.backend.core.base.response.PaginationResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/environments")
public class EnvironmentProfileController {

    private final EnvironmentProfileService environmentProfileService;
    private final ProfileService profileService;
    private final PaginationMapper paginationMapper;

    public EnvironmentProfileController(
            EnvironmentProfileService environmentProfileService,
            ProfileService profileService,
            PaginationMapper paginationMapper
    ) {
        this.environmentProfileService = environmentProfileService;
        this.profileService = profileService;
        this.paginationMapper = paginationMapper;
    }

    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<PaginationResponse<EnvironmentProfileResponse>> listProfiles(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<EnvironmentProfileResponse> profiles = environmentProfileService
                .listProfiles(appUserPrincipal.getId(), page, size)
                .map(this::toResponse);
        return ApiResponse.success(paginationMapper.toResponse(profiles));
    }

    @GetMapping("/{environmentId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<EnvironmentProfileResponse> getProfile(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID environmentId
    ) {
        return ApiResponse.success(toResponse(environmentProfileService.getProfileById(appUserPrincipal.getId(), environmentId)));
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<EnvironmentProfileResponse> createProfile(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @Valid @RequestBody EnvironmentProfileCreateRequest request
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());
        return ApiResponse.success(toResponse(environmentProfileService.createProfile(appUser, request)));
    }

    @PutMapping("/{environmentId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<EnvironmentProfileResponse> updateProfile(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID environmentId,
            @Valid @RequestBody EnvironmentProfileUpdateRequest request
    ) {
        return ApiResponse.success(toResponse(environmentProfileService.updateProfile(
                appUserPrincipal.getId(),
                environmentId,
                request
        )));
    }

    @DeleteMapping("/{environmentId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Map<String, String>> deleteProfile(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID environmentId
    ) {
        environmentProfileService.deleteProfile(appUserPrincipal.getId(), environmentId);
        return ApiResponse.success(Map.of("message", "Environment deleted successfully"));
    }

    @GetMapping("/{environmentId}/config")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<EnvironmentProfileConfigResponse> getConfig(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID environmentId
    ) {
        return ApiResponse.success(environmentProfileService.getConfig(appUserPrincipal.getId(), environmentId));
    }

    @PutMapping("/{environmentId}/config/env/{key}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<EnvironmentProfileConfigResponse> upsertEnvVar(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID environmentId,
            @PathVariable String key,
            @Valid @RequestBody FunctionVersionEnvVarRequest request
    ) {
        return ApiResponse.success(environmentProfileService.upsertEnvVar(
                appUserPrincipal.getId(),
                environmentId,
                key,
                request.value()
        ));
    }

    @PutMapping("/{environmentId}/config/secrets/{key}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<EnvironmentProfileConfigResponse> upsertSecret(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID environmentId,
            @PathVariable String key,
            @Valid @RequestBody FunctionVersionSecretRequest request
    ) {
        return ApiResponse.success(environmentProfileService.upsertSecret(
                appUserPrincipal.getId(),
                environmentId,
                key,
                request.value()
        ));
    }

    @DeleteMapping("/{environmentId}/config/env/{key}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<EnvironmentProfileConfigResponse> deleteEnvVar(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID environmentId,
            @PathVariable String key
    ) {
        return ApiResponse.success(environmentProfileService.deleteEnvVar(appUserPrincipal.getId(), environmentId, key));
    }

    @DeleteMapping("/{environmentId}/config/secrets/{key}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<EnvironmentProfileConfigResponse> deleteSecret(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID environmentId,
            @PathVariable String key
    ) {
        return ApiResponse.success(environmentProfileService.deleteSecret(appUserPrincipal.getId(), environmentId, key));
    }

    private EnvironmentProfileResponse toResponse(EnvironmentProfile profile) {
        return new EnvironmentProfileResponse(
                profile.getId(),
                profile.getEnvironmentKey(),
                profile.getName(),
                profile.getDescription(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}
