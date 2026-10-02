package com.funchole.backend.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.controlplane.config.GatewayNetworkProperties;
import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.dto.DatabaseResponse;
import com.funchole.backend.controlplane.dto.DomainResponse;
import com.funchole.backend.controlplane.dto.EnvironmentProfileConfigResponse;
import com.funchole.backend.controlplane.dto.EnvironmentProfileResponse;
import com.funchole.backend.controlplane.mapper.CustomDomainMapper;
import com.funchole.backend.controlplane.service.CustomDomainService;
import com.funchole.backend.controlplane.service.GatewayCertificateService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class McpInfrastructureToolsTests {
    private final EnvironmentProfileMcpTools environments = mock(EnvironmentProfileMcpTools.class);
    private final FlowConfigurationMcpTools bindings = mock(FlowConfigurationMcpTools.class);
    private final DatabaseMcpTools databases = mock(DatabaseMcpTools.class);
    private final GatewayMcpTools gateways = mock(GatewayMcpTools.class);
    private final DomainMcpTools domains = mock(DomainMcpTools.class);
    private final CustomDomainMcpTools customDomains = mock(CustomDomainMcpTools.class);
    private final FunctionMcpTools functions = mock(FunctionMcpTools.class);
    private final FlowMcpTools flows = mock(FlowMcpTools.class);
    private final FlowVersionMcpTools versions = mock(FlowVersionMcpTools.class);
    private final McpInfrastructureTools tools = new McpInfrastructureTools(environments, bindings, databases,
            gateways, domains, customDomains, mock(CustomDomainService.class), mock(CustomDomainMapper.class),
            mock(GatewayCertificateService.class), mock(GatewayNetworkProperties.class), functions, flows, versions);
    private final UUID target = UUID.randomUUID();

    @Test
    void rejectsConflictingBindingsBeforeAnyFacadeCall() {
        String environment = McpReference.of("environments", target);
        var request = new McpInfrastructureTools.ConfigRequest(null, null, null, null, null, null,
                McpReference.of("flows", UUID.randomUUID()),
                List.of(new McpInfrastructureTools.EnvironmentBinding(environment, 100)), List.of(environment), null, null, true);
        assertThat(tools.configure(request).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(environments, bindings, flows, databases);
    }

    @Test
    void validatesEveryConfigKeyBeforeCreatingProfile() {
        var request = profile(null, "prod", "Production", Map.of("GOOD", "ok", "bad-key", "no"), null, null);
        assertThat(tools.configure(request).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(environments);
    }

    @Test
    void rejectsEnvSecretCollisionBeforeCreatingProfile() {
        var request = profile(null, "prod", "Production", Map.of("TOKEN", "public"), Map.of("TOKEN", "secret"), null);
        assertThat(tools.configure(request).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(environments);
    }

    @Test
    void omittedConfigurationIsUnchanged() {
        String reference = McpReference.of("environments", target);
        when(environments.getEnvironment(target.toString())).thenReturn(new EnvironmentProfileResponse(target,
                "prod", "Production", "Keep description", null, null));
        when(environments.getEnvironmentConfig(target.toString())).thenReturn(new EnvironmentProfileConfigResponse(target, List.of(), List.of()));
        assertThat(tools.configure(profile(reference, null, null, null, null, true)).ok()).isTrue();
        verify(environments, never()).updateEnvironment(anyString(), anyString(), any());
        verify(environments, never()).setEnvironmentEnvVar(anyString(), anyString(), anyString());
        verify(environments, never()).setEnvironmentSecret(anyString(), anyString(), anyString());
    }

    @Test
    void existingProfilesRequireExplicitLiveGuard() {
        assertThat(tools.configure(profile(McpReference.of("environments", target), null, null,
                Map.of("MODE", "prod"), null, null)).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(environments);
    }

    @Test
    void databasePasswordIsExcludedFromReceipt() throws Exception {
        when(databases.createDatabase(anyString(), anyString(), anyInt(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new DatabaseResponse(target, "primary", "POSTGRES", "db.example.com", 5432, "app", "user", true, null, null));
        var result = tools.connectDatabase(database(null, "never-echo-this", null));
        assertThat(result.ok()).isTrue();
        assertThat(result.reference()).isEqualTo(McpReference.of("databases", target));
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("never-echo-this", "passwordSecretRef");
        verify(databases).createDatabase("primary", "db.example.com", 5432, "app", "user", "never-echo-this", true);
        verify(databases, never()).revealDatabasePassword(anyString());
    }

    @Test
    void databaseUpdatesRequireLiveGuardBeforeReadingOrWriting() {
        assertThat(tools.connectDatabase(database(McpReference.of("databases", target), null, false)).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(databases);
    }

    @Test
    void omittedSslSettingPreservesExistingConnection() {
        when(databases.getDatabase(target.toString())).thenReturn(new DatabaseResponse(target,
                "primary", "POSTGRES", "db.example.com", 5432, "app", "user", false, null, null));
        var result = tools.connectDatabase(database(McpReference.of("databases", target), null, true));
        assertThat(result.ok()).isTrue();
        verify(databases).updateDatabase(target.toString(), "primary", "db.example.com", 5432, "app", "user", null, false);
    }

    @Test
    void newBaseDomainReturnsRealTxtMetadata() throws Exception {
        when(domains.createDomain("example.com")).thenReturn(new DomainResponse(target, "example.com", DomainStatus.PENDING, "challenge", null, null));
        var result = tools.claimDomain(new McpInfrastructureTools.DomainRequest(null, "example.com", null, McpInfrastructureTools.DomainKind.BASE, null));
        assertThat(result.ok()).isTrue();
        assertThat(result.reference()).isEqualTo(McpReference.of("domains", target));
        var receipt = (McpInfrastructureTools.DomainClaim) result.data();
        assertThat(receipt.dnsRequirements()).containsExactly(new McpInfrastructureTools.DnsRequirement("TXT", "funchole-" + target + ".example.com", "challenge"));
        verify(domains, never()).initiateDomainVerification(anyString());
    }

    @Test
    void existingDomainIsInspectedWithoutImplicitDnsCheck() {
        when(domains.getDomain(target.toString())).thenReturn(new DomainResponse(target, "example.com", DomainStatus.PENDING, "challenge", null, null));
        assertThat(tools.claimDomain(new McpInfrastructureTools.DomainRequest(McpReference.of("domains", target), null, null, null, null)).ok()).isTrue();
        verify(domains, never()).initiateDomainVerification(anyString());
    }

    @Test
    void rejectsNewAndExistingDomainInputs() {
        var request = new McpInfrastructureTools.DomainRequest(McpReference.of("domains", target), "example.com", null, null, true);
        assertThat(tools.claimDomain(request).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(domains, customDomains);
    }

    @Test
    void rejectsUnsupportedRetirementKindsAndDispositionsBeforeCalls() {
        assertThat(tools.retire(McpReference.of("domains", target), McpInfrastructureTools.Disposition.DELETE, true).code()).isEqualTo("INVALID_INPUT");
        assertThat(tools.retire(McpReference.of("databases", target), McpInfrastructureTools.Disposition.ARCHIVE, true).code()).isEqualTo("INVALID_INPUT");
        verifyNoInteractions(domains, databases, versions);
    }

    @Test
    void gatewayDeletionIsExplicitAndDisclosesPermanentEffect() {
        var result = tools.retire(McpReference.of("gateways", target), McpInfrastructureTools.Disposition.DELETE, true);
        assertThat(result.ok()).isTrue();
        assertThat(result.reference()).isEqualTo(McpReference.of("gateways", target));
        verify(gateways).getGateway(target.toString());
        verify(gateways).deleteGateway(target.toString());
        assertThat(result.warnings()).anyMatch(w -> w.contains("permanent"));
    }

    @Test
    void partialConfigFailureKeepsSurvivingReferenceAndNeverEchoesSecret() throws Exception {
        when(environments.createEnvironment("prod", "Production", null)).thenReturn(new EnvironmentProfileResponse(target, "prod", "Production", null, null, null));
        when(environments.setEnvironmentSecret(target.toString(), "TOKEN", "secret-value"))
                .thenThrow(new IllegalStateException("secret-value"));
        var result = tools.configure(profile(null, "prod", "Production", null, Map.of("TOKEN", "secret-value"), null));
        assertThat(result.code()).isEqualTo("PARTIAL_FAILURE");
        assertThat(result.reference()).isEqualTo(McpReference.of("environments", target));
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("secret-value");
    }

    private static McpInfrastructureTools.ConfigRequest profile(String reference, String key, String name,
            Map<String, String> env, Map<String, String> secrets, Boolean live) {
        return new McpInfrastructureTools.ConfigRequest(reference, key, name, null, env, secrets, null, null, null, null, null, live);
    }

    private static McpInfrastructureTools.DatabaseRequest database(String reference, String password, Boolean live) {
        return new McpInfrastructureTools.DatabaseRequest(reference, "primary", "db.example.com", 5432, "app", "user", password, null, live);
    }
}
