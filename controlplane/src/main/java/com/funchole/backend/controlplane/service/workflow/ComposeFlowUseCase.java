package com.funchole.backend.controlplane.service.workflow;

import com.funchole.backend.controlplane.constant.FlowStepComponentType;
import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.constant.FunctionVersionStatus;
import com.funchole.backend.controlplane.dto.FlowCreateRequest;
import com.funchole.backend.controlplane.dto.FlowStepCreateRequest;
import com.funchole.backend.controlplane.dto.FlowVersionCreateRequest;
import com.funchole.backend.controlplane.service.FlowService;
import com.funchole.backend.controlplane.service.FlowStepService;
import com.funchole.backend.controlplane.service.FlowVersionService;
import com.funchole.backend.controlplane.service.FunctionVersionService;
import com.funchole.backend.controlplane.service.GatewayService;
import com.funchole.backend.controlplane.service.ProfileService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates an entire ordered draft in one relational transaction. */
@Service
public class ComposeFlowUseCase {
    public record Step(String key, FlowStepComponentType type, UUID componentId, UUID versionId, String metadata) { }
    public record Command(UUID flowId, String key, String name, UUID gatewayId, String httpMethod,
                          String path, Integer priority, String runtime, List<Step> steps) { }
    public record Result(UUID flowId, UUID versionId) { }

    private final ProfileService profiles;
    private final FlowService flows;
    private final FlowVersionService versions;
    private final FlowStepService flowSteps;
    private final FunctionVersionService functions;
    private final GatewayService gateways;

    public ComposeFlowUseCase(ProfileService profiles, FlowService flows, FlowVersionService versions,
            FlowStepService flowSteps, FunctionVersionService functions, GatewayService gateways) {
        this.profiles = profiles;
        this.flows = flows;
        this.versions = versions;
        this.flowSteps = flowSteps;
        this.functions = functions;
        this.gateways = gateways;
    }

    @Transactional(rollbackFor = Exception.class)
    public Result execute(UUID userId, Command c) throws Exception {
        require(userId != null && c != null && c.runtime() != null && c.steps() != null
                && !c.steps().isEmpty() && c.steps().size() <= 100);
        if (c.flowId() == null) {
            require(c.key() != null && c.key().matches("[A-Za-z0-9_.-]{1,150}")
                    && c.name() != null && !c.name().isBlank() && c.name().length() <= 255 && c.gatewayId() != null);
            gateways.getGatewayById(userId, c.gatewayId());
        } else {
            require(c.key() == null && c.name() == null && c.gatewayId() == null
                    && c.httpMethod() == null && c.path() == null && c.priority() == null);
            flows.getFlowById(userId, c.flowId());
        }
        Set<String> keys = new HashSet<>();
        for (Step step : c.steps()) {
            require(step != null && step.key() != null && step.key().matches("[A-Za-z0-9_.-]{1,150}")
                    && keys.add(step.key()) && step.type() != null && step.componentId() != null && step.versionId() != null);
            validateComponent(userId, c.runtime(), step, new HashSet<>());
        }
        FlowStepComponentType last = c.steps().getLast().type();
        if ("STATIC".equals(c.runtime())) require(c.steps().size() == 1 && last == FlowStepComponentType.FUNCTION);
        else {
            require("NODE".equals(c.runtime()) && (last == FlowStepComponentType.RESPONSE || last == FlowStepComponentType.SUB_FLOW));
            for (int i = 0; i < c.steps().size() - 1; i++) require(c.steps().get(i).type() != FlowStepComponentType.RESPONSE);
        }
        UUID flowId = c.flowId();
        if (flowId == null) flowId = flows.createFlow(profiles.loadUserById(userId), new FlowCreateRequest(
                c.key(), c.name(), null, c.gatewayId(), c.httpMethod(), c.path(), c.priority())).getId();
        UUID draftId = versions.createDraftVersion(userId, flowId, new FlowVersionCreateRequest(c.runtime(), null)).getId();
        for (int i = 0; i < c.steps().size(); i++) {
            Step step = c.steps().get(i);
            flowSteps.createStep(userId, flowId, draftId, new FlowStepCreateRequest(step.key(), step.type(),
                    i + 1, step.componentId(), step.versionId(), step.metadata()));
        }
        return new Result(flowId, draftId);
    }

    private void validateComponent(UUID userId, String runtime, Step step, Set<UUID> visiting) {
        if (step.type() == FlowStepComponentType.SUB_FLOW) {
            var version = versions.getVersionById(userId, step.componentId(), step.versionId());
            require(version.getStatus() == FlowVersionStatus.ADOPTED && runtime.equals(version.getRuntime()));
            require(visiting.size() < 20 && visiting.add(step.versionId()));
            try {
                List<Step> nested = flowSteps.listSteps(userId, step.componentId(), step.versionId()).stream()
                        .map(s -> new Step(s.getStepKey(), s.getComponentType(), s.getComponentId(),
                                s.getComponentVersionId(), s.getMetadata())).toList();
                require(!nested.isEmpty() && nested.size() <= 100);
                for (Step child : nested) validateComponent(userId, runtime, child, visiting);
                FlowStepComponentType last = nested.getLast().type();
                if ("STATIC".equals(runtime)) require(nested.size() == 1 && last == FlowStepComponentType.FUNCTION);
                else {
                    require(last == FlowStepComponentType.RESPONSE || last == FlowStepComponentType.SUB_FLOW);
                    for (int i = 0; i < nested.size() - 1; i++) require(nested.get(i).type() != FlowStepComponentType.RESPONSE);
                }
            } finally { visiting.remove(step.versionId()); }
        } else {
            var version = functions.getVersionById(userId, step.componentId(), step.versionId());
            require(version.getStatus() == FunctionVersionStatus.READY && runtime.equals(version.getRuntime()));
        }
    }

    private static void require(boolean valid) { if (!valid) throw new IllegalArgumentException("Invalid compose request"); }
}
