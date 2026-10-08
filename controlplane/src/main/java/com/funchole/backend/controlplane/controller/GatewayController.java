package com.funchole.backend.controlplane.controller;

import com.funchole.backend.controlplane.dto.GatewayCreateRequest;
import com.funchole.backend.controlplane.dto.GatewayResponse;
import com.funchole.backend.controlplane.dto.GatewayUpdateRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.mapper.GatewayMapper;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.GatewayService;
import com.funchole.backend.controlplane.service.ProfileService;
import com.funchole.backend.core.base.mapper.PaginationMapper;
import com.funchole.backend.core.base.response.ApiResponse;
import com.funchole.backend.core.base.response.PaginationResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.data.domain.Page;
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
@RequestMapping("/api/v1/gateways")
public class GatewayController {
    private final GatewayService gatewayService;
    private final ProfileService profileService;
    private final GatewayMapper gatewayMapper;
    private final PaginationMapper paginationMapper;

    public GatewayController(
            GatewayService gatewayService,
            ProfileService profileService,
            GatewayMapper gatewayMapper,
            PaginationMapper paginationMapper
    ) {
        this.gatewayService = gatewayService;
        this.profileService = profileService;
        this.gatewayMapper = gatewayMapper;
        this.paginationMapper = paginationMapper;
    }

    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<PaginationResponse<GatewayResponse>> listGateways(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Page<GatewayResponse> gateways = gatewayService.listGateways(appUserPrincipal.getId(), page, size)
                .map(gatewayMapper::toResponse);
        return ApiResponse.success(paginationMapper.toResponse(gateways));
    }

    @GetMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<GatewayResponse> getGatewayById(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID id
    ) {
        Gateway gateway = gatewayService.getGatewayById(appUserPrincipal.getId(), id);
        return ApiResponse.success(gatewayMapper.toResponse(gateway));
    }

    @PostMapping
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<GatewayResponse> createGateway(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @Valid @RequestBody GatewayCreateRequest request
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());
        Gateway gateway = gatewayService.createGateway(appUser, request);
        return ApiResponse.success(gatewayMapper.toResponse(gateway));
    }

    @PutMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<GatewayResponse> updateGateway(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID id,
            @Valid @RequestBody GatewayUpdateRequest request
    ) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());
        Gateway gateway = gatewayService.updateGateway(appUser, id, request);
        return ApiResponse.success(gatewayMapper.toResponse(gateway));
    }

    @DeleteMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<Map<String, String>> deleteGateway(
            @AuthenticationPrincipal AppUserPrincipal appUserPrincipal,
            @PathVariable UUID id
    ) {
        gatewayService.deleteGateway(appUserPrincipal.getId(), id);
        return ApiResponse.success(Map.of("message", "Gateway deleted successfully"));
    }
}
