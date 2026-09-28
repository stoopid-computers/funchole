package com.funchole.backend.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.controlplane.config.CloudModeProperties;
import com.funchole.backend.controlplane.config.GoogleAuthProperties;
import com.funchole.backend.controlplane.config.SecurityProperties;
import com.funchole.backend.controlplane.config.TenantDatabaseProperties;
import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Database;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.repository.AppDomainRepository;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.DatabaseRepository;
import com.funchole.backend.controlplane.repository.GatewayRepository;
import com.funchole.backend.controlplane.repository.PackageRepository;
import com.funchole.backend.controlplane.repository.UserPackageRepository;
import com.funchole.backend.controlplane.security.JwtService;
import com.funchole.backend.controlplane.security.JwtToken;
import com.funchole.backend.core.base.exception.ForbiddenException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises both authorization paths {@link GoogleAuthService} makes,
 * directly against hand-built {@link GoogleIdToken.Payload} instances
 * rather than faking or mocking {@code GoogleIdTokenVerifier#verify},
 * which is Google's own SDK code, not this class's own logic. Uses real
 * repositories/services (backed by the test Postgres, matching this
 * module's own established pattern) rather than hand-faking them, since
 * they're full {@code JpaRepository}/multi-collaborator services, not
 * small app-owned interfaces worth faking.
 *
 * <p>{@code @Transactional}: the cloud-mode tests build their own {@code
 * GatewayService}/{@code CloudSignupService} instances (to scope {@code
 * CloudModeProperties} per scenario) rather than using the app's real
 * {@code @Autowired} beans, which bypasses Spring's {@code @Transactional}
 * AOP proxying on {@code createGateway}/{@code signUp}. Without an ambient
 * transaction here, each repository call inside that chain would open and
 * close its own separate Hibernate session, and a later step (loading the
 * Gateway's lazy {@code AppDomain} to build its certificate hostname) would
 * fail with a cross-session {@code LazyInitializationException} - purely a
 * test-construction artifact, not a production one, since production always
 * calls through the real proxied beans.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class GoogleAuthServiceTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("funchole")
            .withUsername("test")
            .withPassword("test");

    // Stands in for the separate tenant-db server (not @ServiceConnection -
    // this must stay a second, independent server, never wired up as the
    // app's own primary datasource).
    @Container
    static PostgreSQLContainer<?> tenantDbPostgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("postgres")
            .withUsername("tenant_admin")
            .withPassword("tenant_admin");

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private AppDomainRepository appDomainRepository;

    @Autowired
    private GatewayRepository gatewayRepository;

    @Autowired
    private PackageRepository packageRepository;

    @Autowired
    private UserPackageRepository userPackageRepository;

    @Autowired
    private DomainService domainService;

    @Autowired
    private DatabaseRepository databaseRepository;

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private GatewayCertificateService gatewayCertificateService;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PackageLimitService packageLimitService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private JwtService jwtService;

    // ---------- self-hosted single-admin path (cloud mode off) ----------

    @Test
    void issuesATokenForTheAdminAccountWhenTheEmailIsAllowlisted() {
        GoogleAuthService service = selfHostedService(List.of("Me@Gmail.com"));

        JwtToken token = service.authenticateVerifiedPayload(payload("me@gmail.com", true));

        assertThat(token.token()).isNotBlank();
    }

    @Test
    void allowlistMatchIsCaseInsensitive() {
        GoogleAuthService service = selfHostedService(List.of("me@gmail.com"));

        JwtToken token = service.authenticateVerifiedPayload(payload("ME@GMAIL.COM", true));

        assertThat(token.token()).isNotBlank();
    }

    @Test
    void rejectsAnEmailNotOnTheAllowlist() {
        GoogleAuthService service = selfHostedService(List.of("me@gmail.com"));

        assertThatThrownBy(() -> service.authenticateVerifiedPayload(payload("someone-else@gmail.com", true)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("someone-else@gmail.com");
    }

    @Test
    void rejectsAnUnverifiedEmailEvenIfAllowlisted() {
        GoogleAuthService service = selfHostedService(List.of("me@gmail.com"));

        assertThatThrownBy(() -> service.authenticateVerifiedPayload(payload("me@gmail.com", false)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void rejectsEverythingWhenNoEmailsAreAllowlisted() {
        GoogleAuthService service = selfHostedService(List.of());

        assertThatThrownBy(() -> service.authenticateVerifiedPayload(payload("me@gmail.com", true)))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---------- cloud self-registration path (cloud mode on) ----------

    @Test
    void firstTimeGoogleSignInSelfRegistersANewUserWithTheFreePackageAndADefaultGateway() {
        GoogleAuthService service = cloudService(verifiedPlatformDomain());
        String email = "new-cloud-user-" + UUID.randomUUID() + "@gmail.com";

        JwtToken token = service.authenticateOrRegisterCloudUser(payload(email, true));

        assertThat(token.token()).isNotBlank();
        AppUser created = appUserRepository.findByEmail(email).orElseThrow();
        assertThat(userPackageRepository.findByAppUser_Id(created.getId())).isPresent();
        List<Gateway> gateways = gatewayRepository.findAllByAppUser_Id(created.getId(), Pageable.unpaged()).getContent();
        assertThat(gateways).hasSize(1);
        assertThat(gateways.get(0).getName()).isEqualTo("Default Gateway");

        List<Database> databases = databaseRepository.findAllByAppUser_IdAndDeletedAtIsNull(created.getId(), Pageable.unpaged()).getContent();
        assertThat(databases).hasSize(1);
        Database database = databases.get(0);
        assertThat(database.getName()).isEqualTo("defaultdb");
        // The real provisioning DDL actually ran against tenant-db - connect
        // with the generated credentials directly, proving this isn't just
        // a Database row with no matching Postgres role/database behind it.
        String password = databaseService.revealPassword(created.getId(), database.getId());
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://" + database.getHost() + ":" + database.getPort() + "/" + database.getDatabaseName(),
                database.getUsername(), password)) {
            assertThat(connection.isValid(5)).isTrue();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    @Test
    void secondSignInWithTheSameEmailLogsIntoTheSameAccountRatherThanRegisteringAgain() {
        GoogleAuthService service = cloudService(verifiedPlatformDomain());
        String email = "returning-cloud-user-" + UUID.randomUUID() + "@gmail.com";

        service.authenticateOrRegisterCloudUser(payload(email, true));
        AppUser firstAccount = appUserRepository.findByEmail(email).orElseThrow();

        service.authenticateOrRegisterCloudUser(payload(email, true));
        AppUser secondLookup = appUserRepository.findByEmail(email).orElseThrow();

        assertThat(secondLookup.getId()).isEqualTo(firstAccount.getId());
        assertThat(gatewayRepository.findAllByAppUser_Id(firstAccount.getId(), Pageable.unpaged()).getContent())
                .hasSize(1);
    }

    @Test
    void cloudSignInRejectsAnUnverifiedEmail() {
        GoogleAuthService service = cloudService(verifiedPlatformDomain());

        assertThatThrownBy(() -> service.authenticateOrRegisterCloudUser(payload("unverified@gmail.com", false)))
                .isInstanceOf(BadCredentialsException.class);
    }

    private GoogleAuthService selfHostedService(List<String> allowedEmails) {
        GoogleAuthProperties properties = new GoogleAuthProperties("test-client-id", allowedEmails);
        CloudSignupService unusedOnThisPath = cloudSignupService(false);
        return new GoogleAuthService(
                null, properties, securityProperties, new CloudModeProperties(false),
                appUserRepository, unusedOnThisPath, jwtService);
    }

    /**
     * {@code platformDomain} isn't wired anywhere directly anymore - its
     * only job is to exist as a {@code VERIFIED} row before this is called,
     * so {@code GatewayService.resolveDomainForNewGateway}'s random pick
     * (exercised transitively through {@code CloudSignupService}) has at
     * least one domain to choose from. Callers pass
     * {@code cloudService(verifiedPlatformDomain())} to make that ordering
     * explicit at the call site.
     */
    private GoogleAuthService cloudService(AppDomain platformDomain) {
        GoogleAuthProperties properties = new GoogleAuthProperties("test-client-id", List.of());
        CloudSignupService cloudSignupService = cloudSignupService(true);
        return new GoogleAuthService(
                null, properties, securityProperties, new CloudModeProperties(true),
                appUserRepository, cloudSignupService, jwtService);
    }

    /**
     * Builds its own {@code GatewayService} with a {@code CloudModeProperties}
     * matching the caller's scenario, rather than using the app's real
     * {@code @Autowired} bean (which reads the actual {@code CLOUD_MODE_ENABLED}
     * config - always {@code false} in the test profile) - otherwise the
     * cloud-mode self-registration tests below would silently exercise the
     * self-hosted domain-resolution branch instead of the random-pick one.
     */
    private CloudSignupService cloudSignupService(boolean cloudModeEnabled) {
        GatewayService scopedGatewayService = new GatewayService(
                gatewayRepository, domainService, gatewayCertificateService, applicationEventPublisher,
                packageLimitService, new CloudModeProperties(cloudModeEnabled), securityProperties);
        TenantDatabaseProperties tenantDatabaseProperties = new TenantDatabaseProperties(
                tenantDbPostgres.getHost(), tenantDbPostgres.getMappedPort(5432), "tenant_admin", "tenant_admin");
        TenantDatabaseProvisioningService tenantDatabaseProvisioningService =
                new TenantDatabaseProvisioningService(tenantDatabaseProperties);
        return new CloudSignupService(
                appUserRepository, userPackageRepository, packageRepository, databaseRepository,
                scopedGatewayService, databaseService, tenantDatabaseProvisioningService, tenantDatabaseProperties,
                passwordEncoder, applicationEventPublisher);
    }

    private AppDomain verifiedPlatformDomain() {
        AppUser admin = appUserRepository.findByUsername(securityProperties.bootstrapUser().username()).orElseThrow();
        return appDomainRepository.save(AppDomain.create(
                admin, "cloud-platform-" + UUID.randomUUID() + ".example.com", "verify-me", DomainStatus.VERIFIED));
    }

    private GoogleIdToken.Payload payload(String email, boolean emailVerified) {
        return new GoogleIdToken.Payload().setEmail(email).setEmailVerified(emailVerified);
    }
}
