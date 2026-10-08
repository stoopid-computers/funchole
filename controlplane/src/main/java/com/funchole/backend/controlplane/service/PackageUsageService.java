package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.PackageLimitKey;
import com.funchole.backend.controlplane.dto.PackageUsageResponse;
import com.funchole.backend.controlplane.entity.Package;
import com.funchole.backend.controlplane.entity.UserPackage;
import com.funchole.backend.controlplane.repository.AppDomainRepository;
import com.funchole.backend.controlplane.repository.CustomDomainRepository;
import com.funchole.backend.controlplane.repository.DatabaseRepository;
import com.funchole.backend.controlplane.repository.FlowRepository;
import com.funchole.backend.controlplane.repository.FunctionRepository;
import com.funchole.backend.controlplane.repository.GatewayRepository;
import com.funchole.backend.controlplane.repository.PackageRepository;
import com.funchole.backend.controlplane.repository.UserPackageRepository;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a user's plan and current usage for the UI. Usage counts are the same
 * ones {@link PackageLimitService} enforces against, so what the user sees
 * matches what will block them.
 */
@Service
public class PackageUsageService {

    private final PackageLimitService packageLimitService;
    private final UserPackageRepository userPackageRepository;
    private final PackageRepository packageRepository;
    private final GatewayRepository gatewayRepository;
    private final FlowRepository flowRepository;
    private final FunctionRepository functionRepository;
    private final AppDomainRepository appDomainRepository;
    private final DatabaseRepository databaseRepository;
    private final CustomDomainRepository customDomainRepository;

    public PackageUsageService(
            PackageLimitService packageLimitService,
            UserPackageRepository userPackageRepository,
            PackageRepository packageRepository,
            GatewayRepository gatewayRepository,
            FlowRepository flowRepository,
            FunctionRepository functionRepository,
            AppDomainRepository appDomainRepository,
            DatabaseRepository databaseRepository,
            CustomDomainRepository customDomainRepository
    ) {
        this.packageLimitService = packageLimitService;
        this.userPackageRepository = userPackageRepository;
        this.packageRepository = packageRepository;
        this.gatewayRepository = gatewayRepository;
        this.flowRepository = flowRepository;
        this.functionRepository = functionRepository;
        this.appDomainRepository = appDomainRepository;
        this.databaseRepository = databaseRepository;
        this.customDomainRepository = customDomainRepository;
    }

    @Transactional(readOnly = true)
    public PackageUsageResponse usageFor(UUID appUserId) {
        boolean cloud = packageLimitService.isEnabled();

        Package assigned = cloud
                ? userPackageRepository.findByAppUser_Id(appUserId)
                        .map(UserPackage::getPackageId)
                        .flatMap(packageRepository::findById)
                        .orElse(null)
                : null;

        List<PackageUsageResponse.Limit> limits = Arrays.stream(PackageLimitKey.values())
                .map(key -> {
                    long used = usage(appUserId, key);
                    Integer limit = cloud ? packageLimitService.effectiveLimit(appUserId, key) : null;
                    Long remaining = limit == null ? null : Math.max(limit - used, 0L);
                    return new PackageUsageResponse.Limit(key.name(), limit, used, remaining);
                })
                .toList();

        return new PackageUsageResponse(
                cloud,
                assigned == null ? null : assigned.getKey(),
                assigned == null ? null : assigned.getName(),
                limits
        );
    }

    private long usage(UUID appUserId, PackageLimitKey key) {
        return switch (key) {
            case MAX_GATEWAYS -> gatewayRepository.countByAppUser_Id(appUserId);
            case MAX_FLOWS -> flowRepository.countByAppUser_IdAndDeletedAtIsNull(appUserId);
            case MAX_FUNCTIONS -> functionRepository.countByAppUser_IdAndDeletedAtIsNull(appUserId);
            case MAX_DOMAINS -> appDomainRepository.countByAppUser_Id(appUserId);
            case MAX_DATABASES -> databaseRepository.countByAppUser_IdAndDeletedAtIsNull(appUserId);
            case MAX_CUSTOM_DOMAINS -> customDomainRepository.countByAppUser_Id(appUserId);
        };
    }
}
