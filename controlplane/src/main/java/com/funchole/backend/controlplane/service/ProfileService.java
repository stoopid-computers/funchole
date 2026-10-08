package com.funchole.backend.controlplane.service;

import java.util.UUID;

import org.springframework.data.crossstore.ChangeSetPersister.NotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.funchole.backend.controlplane.config.CloudModeProperties;
import com.funchole.backend.controlplane.config.SecurityProperties;
import com.funchole.backend.controlplane.dto.ProfileRequest;
import com.funchole.backend.controlplane.dto.ProfileResponse;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.repository.AppUserRepository;

@Service
public class ProfileService {
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;
    private final CloudModeProperties cloudModeProperties;

    public ProfileService(
            AppUserRepository appUserRepository,
            PasswordEncoder passwordEncoder,
            SecurityProperties securityProperties,
            CloudModeProperties cloudModeProperties
    ) {
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityProperties = securityProperties;
        this.cloudModeProperties = cloudModeProperties;
    }

    public ProfileResponse toResponse(AppUser appUser) {
        boolean admin = securityProperties.bootstrapUser().username().equalsIgnoreCase(appUser.getUsername());
        return new ProfileResponse(
                appUser.getId(),
                appUser.getUsername(),
                appUser.getEmail(),
                appUser.getFullName(),
                appUser.getCreatedAt(),
                admin,
                cloudModeProperties.enabled()
        );
    }
    
    public AppUser loadUserById(UUID id) throws NotFoundException {
        return appUserRepository.findById(id)
                .orElseThrow(() -> new NotFoundException());
    }

    public AppUser updateUser(AppUser appUser, ProfileRequest profileRequest) throws NotFoundException {

        if (profileRequest.fullName() != null) {
            appUser.setFullName(profileRequest.fullName());
        }
        
        if (profileRequest.password() != null) {
            appUser.setPasswordHash(passwordEncoder.encode(profileRequest.password()));
        }

        if (appUser.isPasswordChangeRequired()) {
            appUser.setPasswordChangeRequired(false);
        }
        
        return appUserRepository.save(appUser);
    }
}
