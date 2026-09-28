package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.dto.ApiKeyCreateRequest;
import com.funchole.backend.controlplane.dto.ApiKeyCreateResponse;
import com.funchole.backend.controlplane.dto.ApiKeyResponse;
import com.funchole.backend.controlplane.dto.ApiKeyRevealResponse;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.mapper.ApiKeyMapper;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.ApiKeyService;
import com.funchole.backend.controlplane.service.ProfileService;
import com.funchole.backend.core.base.response.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final ProfileService profileService;
    private final ApiKeyMapper apiKeyMapper;

    public ApiKeyController(ApiKeyService apiKeyService, ProfileService profileService, ApiKeyMapper apiKeyMapper) {
        this.apiKeyService = apiKeyService;
        this.profileService = profileService;
        this.apiKeyMapper = apiKeyMapper;
    }

    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<List<ApiKeyResponse>> listApiKeys(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal
    ) {
        List<ApiKeyResponse> keys = apiKeyService.listApiKeys(appUserPrincipal.getId())
                .stream()
                .map(apiKeyMapper::toResponse)
                .toList();
        return ApiResponse.success(keys);
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<ApiKeyCreateResponse> createApiKey(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @Valid @RequestBody ApiKeyCreateRequest request
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());
        ApiKeyService.GeneratedApiKey generated = apiKeyService.createApiKey(appUser, request.name());
        return ApiResponse.success(new ApiKeyCreateResponse(
                generated.entity().getId(),
                generated.entity().getName(),
                generated.entity().getKeyPrefix(),
                generated.rawKey(),
                generated.entity().getCreatedAt()
        ));
    }

    @PostMapping("/{apiKeyId}/reveal")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<ApiKeyRevealResponse> revealApiKey(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID apiKeyId
    ) {
        String rawKey = apiKeyService.revealApiKey(appUserPrincipal.getId(), apiKeyId);
        return ApiResponse.success(new ApiKeyRevealResponse(apiKeyId, rawKey));
    }

    @DeleteMapping("/{apiKeyId}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Map<String, String>> revokeApiKey(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID apiKeyId
    ) {
        apiKeyService.revokeApiKey(appUserPrincipal.getId(), apiKeyId);
        return ApiResponse.success(Map.of("message", "API key revoked successfully"));
    }
}
