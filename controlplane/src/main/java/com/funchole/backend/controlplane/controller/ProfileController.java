package com.funchole.backend.controlplane.controller;


import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.funchole.backend.controlplane.dto.PackageUsageResponse;
import com.funchole.backend.controlplane.dto.ProfileRequest;
import com.funchole.backend.controlplane.dto.ProfileResponse;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.PackageUsageService;
import com.funchole.backend.controlplane.service.ProfileService;
import com.funchole.backend.core.base.response.ApiResponse;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;


@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profileService;
    private final PackageUsageService packageUsageService;
    
    public ProfileController(ProfileService profileService, PackageUsageService packageUsageService) {
        this.profileService = profileService;
        this.packageUsageService = packageUsageService;
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<ProfileResponse> getProfile(@AuthenticationPrincipal AppUserPrincipal appUserPrincipal) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());

        return ApiResponse.success(profileService.toResponse(appUser));
    }

    @GetMapping("/package")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<PackageUsageResponse> getPackage(@AuthenticationPrincipal AppUserPrincipal appUserPrincipal) {
        return ApiResponse.success(packageUsageService.usageFor(appUserPrincipal.getId()));
    }

    @PutMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public ApiResponse<ProfileResponse> updateProfile(@AuthenticationPrincipal AppUserPrincipal appUserPrincipal, @Valid @RequestBody ProfileRequest profileRequest) throws NotFoundException {
        AppUser appUser = profileService.loadUserById(appUserPrincipal.getId());
        appUser = profileService.updateUser(appUser, profileRequest);
        
        return ApiResponse.success(profileService.toResponse(appUser));
    }

    
}
