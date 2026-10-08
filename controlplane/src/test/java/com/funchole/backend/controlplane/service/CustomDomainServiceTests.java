package com.funchole.backend.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.controlplane.config.CloudModeProperties;
import com.funchole.backend.controlplane.config.SecurityProperties;
import com.funchole.backend.controlplane.constant.CustomDomainStatus;
import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.constant.PackageLimitKey;
import com.funchole.backend.controlplane.dto.GatewayCreateRequest;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.CustomDomain;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.entity.Package;
import com.funchole.backend.controlplane.entity.UserPackage;
import com.funchole.backend.controlplane.repository.AppDomainRepository;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.CustomDomainRepository;
import com.funchole.backend.controlplane.repository.FlowRepository;
import com.funchole.backend.controlplane.repository.GatewayRepository;
import com.funchole.backend.controlplane.repository.PackageLimitRepository;
import com.funchole.backend.controlplane.repository.PackageRepository;
import com.funchole.backend.controlplane.repository.UserPackageOverrideRepository;
import com.funchole.backend.controlplane.repository.UserPackageRepository;
import com.funchole.backend.core.base.exception.QuotaExceededException;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.funchole.backend.invocation.InvocationEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Covers {@link CustomDomainService}'s own behaviors, beyond the
 * TXT-verification algorithm it deliberately duplicates from
 * {@link DomainService} (see that class's tests for that mechanism):
 * ownership enforcement on attach, the {@code MAX_CUSTOM_DOMAINS} quota, and
 * that re-verification is idempotent once already {@code VERIFIED}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class CustomDomainServiceTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("funchole")
            .withUsername("test")
            .withPassword("test");

    @Autowired
    private CustomDomainRepository customDomainRepository;

    @Autowired
    private GatewayRepository gatewayRepository;

    @Autowired
    private FlowRepository flowRepository;

    @Autowired
    private AppDomainRepository appDomainRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private DomainService domainService;

    @Autowired
    private GatewayCertificateService gatewayCertificateService;

    @Autowired
    private CustomDomainCertificateService customDomainCertificateService;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PackageLimitService packageLimitService;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private PackageRepository packageRepository;

    // The registry wires the NATS publisher eagerly; mock it so the context starts without NATS.
    @MockitoBean
    InvocationEventPublisher publisher;

    @Autowired
    private UserPackageRepository userPackageRepository;

    @Autowired
    private PackageLimitRepository packageLimitRepository;

    @Autowired
    private UserPackageOverrideRepository userPackageOverrideRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void attachingToAnotherUsersGatewayFailsWithResourceNotFound() {
        CustomDomainService service = customDomainService(false);
        AppUser owner = freshCloudUser();
        Gateway ownersGateway = createGatewayFor(owner);
        AppUser someoneElse = freshCloudUser();

        assertThatThrownBy(() -> service.attachCustomDomain(someoneElse, ownersGateway.getId(), "hello.example.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void attachingAnAlreadyConnectedHostnameIsAConflictNotAServerError() {
        CustomDomainService service = customDomainService(false);
        AppUser first = freshCloudUser();
        service.attachCustomDomain(first, createGatewayFor(first).getId(), "taken.example.com");
        AppUser second = freshCloudUser();
        Gateway secondGateway = createGatewayFor(second);

        assertThatThrownBy(() -> service.attachCustomDomain(second, secondGateway.getId(), "Taken.Example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already connected");
    }

    @Test
    void attachCustomDomainEnforcesTheQuota() {
        AppUser user = assignFreePackage(freshCloudUser());
        insertOverride(user.getId(), PackageLimitKey.MAX_CUSTOM_DOMAINS, 1);
        Gateway gateway = createGatewayFor(user);
        CustomDomainService service = customDomainService(true);

        assertThatCode(() -> service.attachCustomDomain(user, gateway.getId(), "one.example.com"))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> service.attachCustomDomain(user, gateway.getId(), "two.example.com"))
                .isInstanceOf(QuotaExceededException.class);
    }

    @Test
    void initiateCustomDomainVerificationIsIdempotentOnceVerified() {
        AppUser user = freshCloudUser();
        Gateway gateway = createGatewayFor(user);
        CustomDomainService service = customDomainService(false);
        CustomDomain customDomain = service.attachCustomDomain(user, gateway.getId(), "already-verified.example.com");

        // Can't perform a real DNS TXT lookup in a unit test - mark it
        // verified directly, mirroring how DomainServiceTests' own
        // verifiedDomain() fixture constructs an already-VERIFIED AppDomain
        // without a real DNS record either.
        customDomain.markVerified();
        customDomainRepository.saveAndFlush(customDomain);

        CustomDomain result = service.initiateCustomDomainVerification(user.getId(), customDomain.getId());

        assertThat(result.getStatus()).isEqualTo(CustomDomainStatus.VERIFIED);
        assertThat(result.getId()).isEqualTo(customDomain.getId());
    }

    /**
     * Builds its own {@code PackageLimitService} rather than using the
     * autowired bean directly: the test profile's real
     * {@code CloudModeProperties} defaults to disabled, which makes
     * {@code enforce(...)} a silent no-op (see
     * {@code PackageLimitServiceTests#enforceIsANoOpWhenCloudModeIsDisabled}) -
     * the quota test below needs it genuinely enabled to prove enforcement.
     */
    private CustomDomainService customDomainService(boolean cloudModeEnabled) {
        PackageLimitService scopedPackageLimitService = new PackageLimitService(
                new CloudModeProperties(cloudModeEnabled), userPackageRepository, packageLimitRepository, userPackageOverrideRepository);
        return new CustomDomainService(
                customDomainRepository, gatewayRepository, scopedPackageLimitService, customDomainCertificateService);
    }

    private Gateway createGatewayFor(AppUser user) {
        // cloudModeEnabled=true + no appDomainId: the non-admin path that
        // auto-picks a random verified domain (see GatewayServiceTests'
        // nonAdminUserGetsARandomlyChosenVerifiedDomainWhenCloudModeIsEnabled)
        // - simplest way to get an owned, routable Gateway without also
        // needing this fixture user to own an AppDomain themselves.
        GatewayService gatewayService = new GatewayService(
                gatewayRepository, flowRepository, domainService, gatewayCertificateService, applicationEventPublisher,
                packageLimitService, new CloudModeProperties(true), securityProperties);
        verifiedDomain();
        return gatewayService.createGateway(user, new GatewayCreateRequest(
                "Custom Domain Test Gateway", null, null, GatewayStatus.ACTIVE));
    }

    private AppUser bootstrapAdmin() {
        return appUserRepository.findByUsername(securityProperties.bootstrapUser().username()).orElseThrow();
    }

    private AppDomain verifiedDomain() {
        AppUser admin = bootstrapAdmin();
        return appDomainRepository.save(AppDomain.create(
                admin, "custom-domain-test-" + UUID.randomUUID() + ".example.com", "verify-me", DomainStatus.VERIFIED));
    }

    private AppUser freshCloudUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return appUserRepository.saveAndFlush(AppUser.createFromGoogleSignUp(
                "custom_domain_test_" + suffix, "custom-domain-test-" + suffix + "@gmail.com", "Custom Domain Test User",
                "$2a$10$unusable.placeholder.hash.for.tests.only........................"));
    }

    private AppUser assignFreePackage(AppUser user) {
        Package freePackage = packageRepository.findByKey("free").orElseThrow();
        userPackageRepository.save(UserPackage.assign(user, freePackage.getId()));
        return user;
    }

    private void insertOverride(UUID appUserId, PackageLimitKey key, Integer value) {
        jdbcTemplate.update(
                "INSERT INTO user_package_overrides (id, app_user_id, limit_key, limit_value) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), appUserId, key.name(), value);
    }
}
