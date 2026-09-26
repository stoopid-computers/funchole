package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.config.TenantDatabaseProperties;
import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.dto.DatabaseCreateRequest;
import com.funchole.backend.controlplane.dto.GatewayCreateRequest;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Package;
import com.funchole.backend.controlplane.entity.UserPackage;
import com.funchole.backend.controlplane.event.UserSignedUpEvent;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.DatabaseRepository;
import com.funchole.backend.controlplane.repository.PackageRepository;
import com.funchole.backend.controlplane.repository.UserPackageRepository;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-registration for the cloud product (see {@code GoogleAuthService},
 * which calls this the first time a verified Google email has never signed
 * in before). Atomic: a new {@link AppUser}, its {@code free} package
 * assignment, its one auto-provisioned default {@code Gateway}, and its one
 * auto-provisioned default {@code Database} are created together or not at
 * all - a real infra hiccup during provisioning fails the whole sign-up
 * rather than leaving a half-created account (a deliberate simplification
 * for a first version; see the plan's "explicitly out of scope" notes for
 * revisiting this later).
 */
@Service
public class CloudSignupService {

    private static final String FREE_PACKAGE_KEY = "free";
    private static final String IDENTIFIER_CHARACTERS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int IDENTIFIER_LENGTH = 12;
    private static final int IDENTIFIER_MAX_ATTEMPTS = 20;
    private static final int PASSWORD_LENGTH = 32;

    private final AppUserRepository appUserRepository;
    private final UserPackageRepository userPackageRepository;
    private final PackageRepository packageRepository;
    private final DatabaseRepository databaseRepository;
    private final GatewayService gatewayService;
    private final DatabaseService databaseService;
    private final TenantDatabaseProvisioningService tenantDatabaseProvisioningService;
    private final TenantDatabaseProperties tenantDatabaseProperties;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final SecureRandom secureRandom = new SecureRandom();

    public CloudSignupService(
            AppUserRepository appUserRepository,
            UserPackageRepository userPackageRepository,
            PackageRepository packageRepository,
            DatabaseRepository databaseRepository,
            GatewayService gatewayService,
            DatabaseService databaseService,
            TenantDatabaseProvisioningService tenantDatabaseProvisioningService,
            TenantDatabaseProperties tenantDatabaseProperties,
            PasswordEncoder passwordEncoder,
            ApplicationEventPublisher eventPublisher
    ) {
        this.appUserRepository = appUserRepository;
        this.userPackageRepository = userPackageRepository;
        this.packageRepository = packageRepository;
        this.databaseRepository = databaseRepository;
        this.gatewayService = gatewayService;
        this.databaseService = databaseService;
        this.tenantDatabaseProvisioningService = tenantDatabaseProvisioningService;
        this.tenantDatabaseProperties = tenantDatabaseProperties;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public AppUser signUp(String email, String fullName) {
        // Unguessable placeholder - this account only ever authenticates via
        // a verified Google ID token, never a password (see AppUser.createFromGoogleSignUp).
        String passwordHash = passwordEncoder.encode(UUID.randomUUID().toString());
        AppUser appUser = appUserRepository.save(
                AppUser.createFromGoogleSignUp(deriveUniqueUsername(email), email, fullName, passwordHash));

        Package freePackage = packageRepository.findByKey(FREE_PACKAGE_KEY)
                .orElseThrow(() -> new IllegalStateException(
                        "The '" + FREE_PACKAGE_KEY + "' package is not seeded - cannot complete cloud sign-up"));
        userPackageRepository.save(UserPackage.assign(appUser, freePackage.getId()));

        provisionDefaultGateway(appUser);
        provisionDefaultDatabase(appUser);
        eventPublisher.publishEvent(new UserSignedUpEvent(resolveSignupIdentity(appUser)));

        return appUser;
    }

    private String resolveSignupIdentity(AppUser appUser) {
        return appUser.getEmail() != null && !appUser.getEmail().isBlank()
                ? appUser.getEmail()
                : appUser.getUsername();
    }

    /**
     * {@code createGateway} itself picks a random verified admin domain for
     * a non-admin user (see {@code GatewayService.resolveDomainForNewGateway})
     * - a freshly self-registered user is never the bootstrap admin, so this
     * always takes that path.
     */
    private void provisionDefaultGateway(AppUser appUser) {
        gatewayService.createGateway(appUser, new GatewayCreateRequest(
                "Default Gateway", "Auto-provisioned on sign-up", null, GatewayStatus.ACTIVE));
    }

    /**
     * Provisions a real Postgres database/role on the separate tenant-db
     * server (see {@code TenantDatabaseProvisioningService}), then reuses
     * {@code DatabaseService.createDatabase} unchanged for the "write
     * password to OpenBao + persist the Database row" half - only the
     * actual DDL is new here.
     */
    private void provisionDefaultDatabase(AppUser appUser) {
        String identifier = generateUniqueDatabaseIdentifier();
        String password = generateDatabasePassword();

        tenantDatabaseProvisioningService.provisionDatabase(identifier, identifier, password);

        databaseService.createDatabase(appUser, new DatabaseCreateRequest(
                "defaultdb",
                "POSTGRES",
                tenantDatabaseProperties.host(),
                tenantDatabaseProperties.port(),
                identifier,
                identifier,
                password,
                true
        ));
    }

    /**
     * Postgres identifiers used unquoted must start with a letter, hence the
     * fixed {@code fh_} prefix rather than the plain lowercase-alphanumeric
     * charset alone. Checked for global uniqueness (not per-user, unlike
     * {@code deriveUniqueUsername} below) since this app is the only thing
     * that ever creates rows on the shared tenant-db server.
     */
    private String generateUniqueDatabaseIdentifier() {
        for (int attempt = 0; attempt < IDENTIFIER_MAX_ATTEMPTS; attempt++) {
            String candidate = "fh_" + randomIdentifierSuffix();
            if (!databaseRepository.existsByDatabaseName(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to generate a unique tenant database identifier");
    }

    private String randomIdentifierSuffix() {
        StringBuilder builder = new StringBuilder(IDENTIFIER_LENGTH);
        for (int index = 0; index < IDENTIFIER_LENGTH; index++) {
            builder.append(IDENTIFIER_CHARACTERS.charAt(secureRandom.nextInt(IDENTIFIER_CHARACTERS.length())));
        }
        return builder.toString();
    }

    /**
     * Alphanumeric only, same reasoning as the identifier above: this value
     * is inlined directly into DDL (see {@code TenantDatabaseProvisioningService}),
     * so it must never contain a quote or other character needing escaping.
     */
    private String generateDatabasePassword() {
        StringBuilder builder = new StringBuilder(PASSWORD_LENGTH);
        for (int index = 0; index < PASSWORD_LENGTH; index++) {
            builder.append(IDENTIFIER_CHARACTERS.charAt(secureRandom.nextInt(IDENTIFIER_CHARACTERS.length())));
        }
        return builder.toString();
    }

    /**
     * Google gives an email and a display name, not a username - derived
     * from the email's local-part, disambiguated with a numeric suffix on
     * collision (extremely unlikely for a fresh signup, but usernames are
     * unique - see AppUser).
     */
    private String deriveUniqueUsername(String email) {
        String localPart = email.substring(0, Math.max(email.indexOf('@'), 0));
        String base = localPart.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (base.isBlank()) {
            base = "user";
        }
        String candidate = base;
        int suffix = 0;
        while (appUserRepository.existsByUsername(candidate)) {
            suffix++;
            candidate = base + suffix;
        }
        return candidate;
    }
}
