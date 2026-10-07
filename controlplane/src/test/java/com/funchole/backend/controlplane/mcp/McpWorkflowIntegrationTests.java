package com.funchole.backend.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.funchole.backend.controlplane.constant.DomainStatus;
import com.funchole.backend.controlplane.constant.FlowStepComponentType;
import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.constant.GatewayStatus;
import com.funchole.backend.controlplane.dto.FlowResponse;
import com.funchole.backend.controlplane.dto.FlowVersionResponse;
import com.funchole.backend.controlplane.entity.AppDomain;
import com.funchole.backend.controlplane.entity.AppUser;
import com.funchole.backend.controlplane.entity.Function;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.entity.Gateway;
import com.funchole.backend.controlplane.repository.*;
import com.funchole.backend.controlplane.security.AppUserPrincipal;
import com.funchole.backend.controlplane.service.FlowPublicationConflictException;
import com.funchole.backend.controlplane.service.FlowPublicationService;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Postgres transactions, references and ownership. No mocked authoring services. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpWorkflowIntegrationTests {
    @Autowired McpWorkflowTools tools;
    @Autowired McpReadTools reads;
    @Autowired McpFlowPublication publication;
    @Autowired FlowPublicationService sharedPublication;
    @Autowired FlowMcpTools flows;
    @Autowired FlowVersionMcpTools versions;
    @Autowired AppUserRepository users;
    @Autowired AppDomainRepository domains;
    @Autowired GatewayRepository gateways;
    @Autowired FunctionRepository functions;
    @Autowired FunctionVersionRepository functionVersions;
    @Autowired FlowRepository flowRepository;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mockMvc;
    private String gatewayRef;
    private String componentRef;
    private AppUserPrincipal owner;
    private AppUserPrincipal stranger;

    @BeforeEach
    void setup() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            AppUser user = users.save(AppUser.createFromGoogleSignUp("mcp-" + suffix, suffix + "@example.com", "Owner", "unused"));
            AppUser other = users.save(AppUser.createFromGoogleSignUp("other-" + suffix, "other-" + suffix + "@example.com", "Other", "unused"));
            owner = new AppUserPrincipal(user);
            stranger = new AppUserPrincipal(other);
            var domain = domains.save(AppDomain.create(user, suffix + ".example.com", "challenge", DomainStatus.VERIFIED));
            var gateway = gateways.save(Gateway.create(user, domain, "MCP integration", suffix.replace("-", "").substring(0, 10), null, GatewayStatus.ACTIVE));
            gatewayRef = McpReference.of("gateways", gateway.getId());
            var function = functions.save(Function.create(user, "mcp-" + suffix, "MCP component", null, "NODE"));
            var version = FunctionVersion.create(function, 1, "NODE", null);
            version.attachArtifact("test/" + suffix, "zip", "0".repeat(64), 1);
            version.markReady();
            functionVersions.save(version);
            componentRef = McpReference.version("function-versions", function.getId(), version.getId());
        });
        authenticate(owner);
    }

    @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

    @Test
    void prepareReplacementLeavesLiveRouteAndBindingsAloneThenExplicitPublishChangesRoute() {
        String first = composeNew();
        assertThat(tools.publishFlow(new McpWorkflowTools.PublishRequest(first, null, null)).ok()).isTrue();
        String flowRef = flowRef(first);
        var draft = tools.composeFlow(new McpWorkflowTools.ComposeRequest(flowRef, null, null, null, null, null, null,
                null, componentRef, null));
        assertThat(draft.ok()).isTrue();
        FlowResponse before = flowState(first);
        assertThat(before.path()).isEqualTo("/before");
        assertThat(before.activeFlowVersionId()).isEqualTo(McpReference.parse(first).id());
        assertThat(reads.read(flowRef, McpReadTools.View.config, null, null, null).data().toString()).contains("environments=[]", "databases=[]");
        var route = new McpWorkflowTools.RouteInput(gatewayRef, "POST", "/after", 7);
        assertThat(tools.publishFlow(new McpWorkflowTools.PublishRequest(draft.reference(), first, route)).ok()).isTrue();
        FlowResponse after = flowState(first);
        assertThat(after.path()).isEqualTo("/after");
        assertThat(after.httpMethod()).isEqualTo("POST");
        assertThat(after.activeFlowVersionId()).isEqualTo(McpReference.parse(draft.reference()).id());
    }

    @Test
    void stalePublicationCannotChangeLiveRevisionOrRoute() {
        String first = composeNew();
        assertThat(tools.publishFlow(new McpWorkflowTools.PublishRequest(first, null, null)).ok()).isTrue();
        var draft = tools.composeFlow(new McpWorkflowTools.ComposeRequest(flowRef(first), null, null, null, null, null, null,
                null, componentRef, null));
        var result = tools.publishFlow(new McpWorkflowTools.PublishRequest(draft.reference(), null,
                new McpWorkflowTools.RouteInput(gatewayRef, "POST", "/must-not-appear", 9)));
        assertThat(result.code()).isEqualTo("CONFLICT");
        assertThat(flowState(first).path()).isEqualTo("/before");
        assertThat(flowState(first).activeFlowVersionId()).isEqualTo(McpReference.parse(first).id());
        assertThat(versionState(draft.reference()).status()).isEqualTo(FlowVersionStatus.DRAFT);
    }

    @Test
    void restPublicationMakesAnMcpExpectationStale() {
        String first = composeNew();
        var firstRef = McpReference.parse(first);
        sharedPublication.publish(owner.getId(), firstRef.parentId(), firstRef.id(), null, null);

        var draft = tools.composeFlow(new McpWorkflowTools.ComposeRequest(flowRef(first), null, null, null, null, null, null,
                null, componentRef, null));
        assertThat(draft.ok()).isTrue();
        assertThat(tools.publishFlow(new McpWorkflowTools.PublishRequest(draft.reference(), null, null)).code())
                .isEqualTo("CONFLICT");
        assertThat(flowState(first).activeFlowVersionId()).isEqualTo(firstRef.id());
        assertThat(versionState(draft.reference()).status()).isEqualTo(FlowVersionStatus.DRAFT);
    }

    @Test
    void guardedRestPublishReturnsConflictForStaleExpectation() throws Exception {
        String first = composeNew();
        var firstRef = McpReference.parse(first);
        sharedPublication.publish(owner.getId(), firstRef.parentId(), firstRef.id(), null, null);
        var draft = tools.composeFlow(new McpWorkflowTools.ComposeRequest(flowRef(first), null, null, null, null, null, null,
                null, componentRef, null));
        assertThat(draft.ok()).isTrue();

        mockMvc.perform(post("/api/v1/flows/{flowId}/versions/{versionId}/publish",
                        firstRef.parentId(), McpReference.parse(draft.reference()).id())
                        .with(authentication(new UsernamePasswordAuthenticationToken(owner, null, owner.getAuthorities())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
        authenticate(owner);
        assertThat(versionState(draft.reference()).status()).isEqualTo(FlowVersionStatus.DRAFT);
    }

    @Test
    void concurrentRestAndMcpPublishersWithTheSameExpectationCannotBothWin() throws Exception {
        String first = composeNew();
        var firstRef = McpReference.parse(first);
        sharedPublication.publish(owner.getId(), firstRef.parentId(), firstRef.id(), null, null);
        var second = tools.composeFlow(new McpWorkflowTools.ComposeRequest(flowRef(first), null, null, null, null, null, null,
                null, componentRef, null));
        var third = tools.composeFlow(new McpWorkflowTools.ComposeRequest(flowRef(first), null, null, null, null, null, null,
                null, componentRef, null));
        assertThat(second.ok()).isTrue();
        assertThat(third.ok()).isTrue();
        UUID secondId = McpReference.parse(second.reference()).id();
        UUID thirdId = McpReference.parse(third.reference()).id();

        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> a = executor.submit(() -> attemptPublish(start, firstRef.parentId(), secondId, firstRef.id()));
            Future<Object> b = executor.submit(() -> {
                start.await();
                authenticate(owner);
                try {
                    var result = publication.publish(McpReference.parse(third.reference()), firstRef.id(), null, null);
                    return result.ok() ? "published" : "conflict";
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder("published", "conflict");
        }
        assertThat(flowState(first).activeFlowVersionId()).isIn(secondId, thirdId);
        assertThat(List.of(versionState(second.reference()).status(), versionState(third.reference()).status()))
                .containsExactlyInAnyOrder(FlowVersionStatus.ADOPTED, FlowVersionStatus.DRAFT);
    }

    @Test
    void publicationRejectsAnotherTenantBeforeChangingTheDraft() {
        String draft = composeNew();
        var target = McpReference.parse(draft);
        assertThatThrownBy(() -> sharedPublication.publish(stranger.getId(), target.parentId(), target.id(), null, null))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(flowState(draft).activeFlowVersionId()).isNull();
        assertThat(versionState(draft).status()).isEqualTo(FlowVersionStatus.DRAFT);
    }

    private Object attemptPublish(CountDownLatch start, UUID flowId, UUID draftId, UUID expected) throws Exception {
        start.await();
        try {
            sharedPublication.publish(owner.getId(), flowId, draftId, expected, null);
            return "published";
        } catch (FlowPublicationConflictException conflict) {
            return "conflict";
        }
    }

    @Test
    void failedRouteWriteRollsBackAdoptionInActualTransaction() {
        String draft = composeNew();
        var target = McpReference.parse(draft);
        // Deliberately bypass public preflight to test the transactional module's rollback.
        assertThatThrownBy(() -> publication.publish(target, null,
                new McpWorkflowTools.RouteInput(gatewayRef, "GET", "/invalid/*/suffix", 0), McpReference.parse(gatewayRef).id()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(flowState(draft).activeFlowVersionId()).isNull();
        assertThat(flowState(draft).path()).isEqualTo("/before");
        assertThat(versionState(draft).status()).isEqualTo(FlowVersionStatus.DRAFT);
    }

    @Test
    void anotherTenantCannotReadInvokeComposeOrPublishReferences() {
        String draft = composeNew();
        long before = flowRepository.count();
        authenticate(stranger);
        for (String reference : List.of(gatewayRef, componentRef, draft, flowRef(draft))) {
            assertThat(reads.read(reference, null, null, null, null).code()).isEqualTo("NOT_FOUND");
        }
        assertThat(tools.invoke(componentRef, "{}").code()).isEqualTo("NOT_FOUND");
        assertThat(tools.publishFlow(new McpWorkflowTools.PublishRequest(draft, null, null)).code()).isEqualTo("NOT_FOUND");
        assertThat(tools.composeFlow(new McpWorkflowTools.ComposeRequest(flowRef(draft), null, null, null, null, null, null,
                null, componentRef, null)).code()).isEqualTo("NOT_FOUND");
        assertThat(flowRepository.count()).isEqualTo(before);
        var inventory = (McpReadTools.Page) reads.discover(McpReadTools.Scope.flows, null, null, 0, 20).data();
        assertThat(inventory.items()).isEmpty();
    }

    @Test
    void readyComponentCanBeReusedInAdvancedAndSubflowCompositions() {
        String child = composeNew();
        assertThat(tools.publishFlow(new McpWorkflowTools.PublishRequest(child, null, null)).ok()).isTrue();
        String key = "advanced-" + UUID.randomUUID();
        var result = tools.composeFlow(new McpWorkflowTools.ComposeRequest(null, key, key, gatewayRef, "GET", "/advanced", 0,
                McpWorkflowTools.Runtime.NODE, null, List.of(
                new McpWorkflowTools.StepInput("prepare", FlowStepComponentType.FUNCTION, componentRef, null),
                new McpWorkflowTools.StepInput("nested", FlowStepComponentType.SUB_FLOW, child, null))));
        assertThat(result.ok()).isTrue();
        assertThat(((FlowVersionResponse) result.data()).steps()).hasSize(2);
        assertThat(tools.publishFlow(new McpWorkflowTools.PublishRequest(result.reference(), null, null)).ok()).isTrue();
    }

    private String composeNew() {
        String key = "flow-" + UUID.randomUUID();
        var result = tools.composeFlow(new McpWorkflowTools.ComposeRequest(null, key, key, gatewayRef, "GET", "/before", 2,
                null, componentRef, null));
        assertThat(result.ok()).withFailMessage(result.toString()).isTrue();
        return result.reference();
    }
    private FlowResponse flowState(String version) {
        return new TransactionTemplate(transactions).execute(status -> flows.getFlow(McpReference.parse(version).parentId().toString()));
    }
    private FlowVersionResponse versionState(String reference) {
        var ref = McpReference.parse(reference);
        return new TransactionTemplate(transactions).execute(status -> versions.getFlowVersion(ref.parentId().toString(), ref.id().toString()));
    }
    private static String flowRef(String version) { return McpReference.of("flows", McpReference.parse(version).parentId()); }
    private static void authenticate(AppUserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
