package com.funchole.backend.controlplane.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.controlplane.config.CloudModeProperties;
import com.funchole.backend.controlplane.config.SecurityProperties;
import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.dto.GatewayCreateRequest;
import com.funchole.backend.controlplane.dto.GatewayUpdateRequest;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.repository.AppDomainRepository;
import com.funchole.backend.controlplane.repository.AppUserRepository;
import com.funchole.backend.controlplane.repository.FlowRepository;
import com.funchole.backend.controlplane.repository.GatewayRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import com.funchole.backend.invocation.InvocationEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Covers {@link GatewayService#createGateway}'s domain-resolution branch
 * (see {@code resolveDomainForNewGateway}): the bootstrap admin (or anyone,
 * when cloud mode is off) must supply and own an {@code appDomainId}, while
 * a non-admin user under cloud mode gets a randomly chosen verified domain
 * automatically and cannot own one of their own (see
 * {@link DomainServiceTests}).
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class GatewayServiceTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
            .withDatabaseName("funchole")
            .withUsername("test")
            .withPassword("test");

    // The registry wires the NATS publisher eagerly; mock it so the context starts without NATS.
    @MockitoBean
    InvocationEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GatewayRepository gatewayRepository;

    @Autowired
    private FlowRepository flowRepository;

    @Autowired
    private DomainService domainService;

    @Autowired
    private GatewayCertificateService gatewayCertificateService;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private PackageLimitService packageLimitService;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private AppDomainRepository appDomainRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Test
    void nonAdminUserGetsARandomlyChosenVerifiedDomainWhenCloudModeIsEnabled() {
        GatewayService service = gatewayService(true);
        AppUser nonAdmin = freshCloudUser();
        AppDomain verified = verifiedDomain();

        Gateway gateway = service.createGateway(nonAdmin, new GatewayCreateRequest(
                "My Gateway", null, null, GatewayStatus.ACTIVE));

        assertThat(gateway.getAppDomain().getId()).isEqualTo(verified.getId());
    }

    @Test
    void nonAdminUserFailsClearlyWhenNoVerifiedDomainExists() {
        GatewayService service = gatewayService(true);
        AppUser nonAdmin = freshCloudUser();

        assertThatThrownBy(() -> service.createGateway(nonAdmin, new GatewayCreateRequest(
                "My Gateway", null, null, GatewayStatus.ACTIVE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("verified domain");
    }

    @Test
    void adminMustSupplyAnOwnedAppDomainIdEvenWhenCloudModeIsEnabled() {
        GatewayService service = gatewayService(true);
        AppUser admin = bootstrapAdmin();
        AppDomain ownDomain = verifiedDomain();

        assertThatThrownBy(() -> service.createGateway(admin, new GatewayCreateRequest(
                "Admin Gateway", null, null, GatewayStatus.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class);

        Gateway gateway = service.createGateway(admin, new GatewayCreateRequest(
                "Admin Gateway", null, ownDomain.getId(), GatewayStatus.ACTIVE));
        assertThat(gateway.getAppDomain().getId()).isEqualTo(ownDomain.getId());
    }

    @Test
    void nonAdminUserMustSupplyAnOwnedAppDomainIdWhenCloudModeIsDisabled() {
        GatewayService service = gatewayService(false);
        AppUser nonAdmin = freshCloudUser();
        verifiedDomain(); // exists, but not owned by nonAdmin - must not be auto-picked

        assertThatThrownBy(() -> service.createGateway(nonAdmin, new GatewayCreateRequest(
                "My Gateway", null, null, GatewayStatus.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cloudUserCanRenameTheirGatewayWithoutOwningADomain() {
        GatewayService service = gatewayService(true);
        AppUser nonAdmin = freshCloudUser();
        AppDomain verified = verifiedDomain();
        Gateway gateway = service.createGateway(nonAdmin, new GatewayCreateRequest("Mine", null, null, GatewayStatus.ACTIVE));

        Gateway renamed = service.updateGateway(nonAdmin, gateway.getId(),
                new GatewayUpdateRequest("Renamed", "new words", null, GatewayStatus.ACTIVE));

        assertThat(renamed.getName()).isEqualTo("Renamed");
        assertThat(renamed.getAppDomain().getId()).isEqualTo(verified.getId());
    }

    @Test
    void selfHostedUpdateStillNeedsAnOwnedDomain() {
        GatewayService service = gatewayService(false);
        AppUser admin = bootstrapAdmin();
        Gateway gateway = service.createGateway(admin, new GatewayCreateRequest("A", null, verifiedDomain().getId(), GatewayStatus.ACTIVE));

        assertThatThrownBy(() -> service.updateGateway(admin, gateway.getId(),
                new GatewayUpdateRequest("B", null, null, GatewayStatus.ACTIVE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gatewayWithWorkflowsCannotBeDeleted() {
        GatewayService service = gatewayService(true);
        AppUser nonAdmin = freshCloudUser();
        verifiedDomain();
        Gateway gateway = service.createGateway(nonAdmin, new GatewayCreateRequest("Mine", null, null, GatewayStatus.ACTIVE));
        UUID flowId = UUID.randomUUID();
        jdbc.update("INSERT INTO flows (id, app_user_id, gateway_id, flow_key, name, http_method, path) VALUES (?, ?, ?, ?, 'F', 'GET', '/')",
                flowId, nonAdmin.getId(), gateway.getId(), "flw_" + flowId.toString().replace("-", ""));

        assertThatThrownBy(() -> service.deleteGateway(nonAdmin.getId(), gateway.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflows");

        jdbc.update("UPDATE flows SET deleted_at = now() WHERE id = ?", flowId);
        assertThatCode(() -> service.deleteGateway(nonAdmin.getId(), gateway.getId())).doesNotThrowAnyException();
    }

    private GatewayService gatewayService(boolean cloudModeEnabled) {
        return new GatewayService(
                gatewayRepository, flowRepository, domainService, gatewayCertificateService, applicationEventPublisher,
                packageLimitService, new CloudModeProperties(cloudModeEnabled), securityProperties);
    }

    private AppUser bootstrapAdmin() {
        return appUserRepository.findByUsername(securityProperties.bootstrapUser().username()).orElseThrow();
    }

    private AppUser freshCloudUser() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return appUserRepository.saveAndFlush(AppUser.createFromGoogleSignUp(
                "gateway_test_" + suffix, "gateway-test-" + suffix + "@gmail.com", "Gateway Test User",
                "$2a$10$unusable.placeholder.hash.for.tests.only........................"));
    }

    private AppDomain verifiedDomain() {
        AppUser admin = bootstrapAdmin();
        return appDomainRepository.save(AppDomain.create(
                admin, "gateway-test-" + UUID.randomUUID() + ".example.com", "verify-me", DomainStatus.VERIFIED));
    }
}
