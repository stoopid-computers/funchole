package com.funchole.backend.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PackageUsageServiceTests {

    private final UUID userId = UUID.randomUUID();
    private final PackageLimitService limits = mock(PackageLimitService.class);
    private final UserPackageRepository userPackages = mock(UserPackageRepository.class);
    private final PackageRepository packages = mock(PackageRepository.class);
    private final GatewayRepository gateways = mock(GatewayRepository.class);
    private final FlowRepository flows = mock(FlowRepository.class);
    private final FunctionRepository functions = mock(FunctionRepository.class);
    private final AppDomainRepository domains = mock(AppDomainRepository.class);
    private final DatabaseRepository databases = mock(DatabaseRepository.class);
    private final CustomDomainRepository customDomains = mock(CustomDomainRepository.class);
    private PackageUsageService service;

    @BeforeEach
    void setUp() {
        service = new PackageUsageService(limits, userPackages, packages, gateways, flows, functions, domains, databases, customDomains);
        when(gateways.countByAppUser_Id(userId)).thenReturn(1L);
        when(flows.countByAppUser_IdAndDeletedAtIsNull(userId)).thenReturn(3L);
        when(functions.countByAppUser_IdAndDeletedAtIsNull(userId)).thenReturn(20L);
        when(domains.countByAppUser_Id(userId)).thenReturn(0L);
        when(databases.countByAppUser_IdAndDeletedAtIsNull(userId)).thenReturn(1L);
        when(customDomains.countByAppUser_Id(userId)).thenReturn(0L);
    }

    private PackageUsageResponse.Limit limit(PackageUsageResponse response, PackageLimitKey key) {
        return response.limits().stream().filter(l -> l.key().equals(key.name())).findFirst().orElseThrow();
    }

    @Test
    void selfHostedHasNoPlanAndNothingIsLimited() {
        when(limits.isEnabled()).thenReturn(false);

        PackageUsageResponse response = service.usageFor(userId);

        assertThat(response.cloudMode()).isFalse();
        assertThat(response.packageKey()).isNull();
        assertThat(response.limits()).hasSize(PackageLimitKey.values().length);
        assertThat(limit(response, PackageLimitKey.MAX_FLOWS).limit()).isNull();
        assertThat(limit(response, PackageLimitKey.MAX_FLOWS).used()).isEqualTo(3);
        assertThat(limit(response, PackageLimitKey.MAX_FLOWS).remaining()).isNull();
    }

    @Test
    void cloudReportsPlanLimitsUsageAndRemaining() {
        UUID packageId = UUID.randomUUID();
        UserPackage assigned = mock(UserPackage.class);
        when(assigned.getPackageId()).thenReturn(packageId);
        Package free = mock(Package.class);
        when(free.getKey()).thenReturn("free");
        when(free.getName()).thenReturn("Free");
        when(limits.isEnabled()).thenReturn(true);
        when(userPackages.findByAppUser_Id(userId)).thenReturn(Optional.of(assigned));
        when(packages.findById(packageId)).thenReturn(Optional.of(free));
        when(limits.effectiveLimit(eq(userId), any(PackageLimitKey.class))).thenReturn(null);
        when(limits.effectiveLimit(userId, PackageLimitKey.MAX_FLOWS)).thenReturn(20);
        when(limits.effectiveLimit(userId, PackageLimitKey.MAX_FUNCTIONS)).thenReturn(20);

        PackageUsageResponse response = service.usageFor(userId);

        assertThat(response.cloudMode()).isTrue();
        assertThat(response.packageKey()).isEqualTo("free");
        assertThat(response.packageName()).isEqualTo("Free");
        assertThat(limit(response, PackageLimitKey.MAX_FLOWS).remaining()).isEqualTo(17);
        // At the limit: nothing remaining, never negative.
        assertThat(limit(response, PackageLimitKey.MAX_FUNCTIONS).remaining()).isZero();
        // An explicit null override means unlimited.
        assertThat(limit(response, PackageLimitKey.MAX_GATEWAYS).limit()).isNull();
        assertThat(ReflectionTestUtils.getField(service, "packageLimitService")).isSameAs(limits);
    }
}
