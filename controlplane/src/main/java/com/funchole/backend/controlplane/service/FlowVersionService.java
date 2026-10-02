package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.constant.FlowStepComponentType;
import com.funchole.backend.controlplane.constant.FlowVersionStatus;
import com.funchole.backend.controlplane.dto.FlowVersionCreateRequest;
import com.funchole.backend.controlplane.entity.Flow;
import com.funchole.backend.controlplane.entity.FlowStep;
import com.funchole.backend.controlplane.entity.FlowVersion;
import com.funchole.backend.controlplane.entity.FunctionVersion;
import com.funchole.backend.controlplane.repository.FlowRepository;
import com.funchole.backend.controlplane.repository.FlowStepRepository;
import com.funchole.backend.controlplane.repository.FlowVersionRepository;
import com.funchole.backend.controlplane.repository.FunctionVersionRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FlowVersionService {
    private static final String DEFAULT_RUNTIME = "NODE";
    private static final String STATIC_RUNTIME = "STATIC";

    private static final Set<FlowStepComponentType> TERMINAL_COMPONENT_TYPES =
            Set.of(FlowStepComponentType.RESPONSE, FlowStepComponentType.SUB_FLOW);

    private final FlowVersionRepository flowVersionRepository;
    private final FlowStepRepository flowStepRepository;
    private final FlowRepository flowRepository;
    private final FlowService flowService;
    private final FlowStepReferenceValidator flowStepReferenceValidator;
    private final FunctionVersionRepository functionVersionRepository;
    private final FlowWriteLock flowWriteLock;

    public FlowVersionService(
            FlowVersionRepository flowVersionRepository,
            FlowStepRepository flowStepRepository,
            FlowRepository flowRepository,
            FlowService flowService,
            FlowStepReferenceValidator flowStepReferenceValidator,
            FunctionVersionRepository functionVersionRepository,
            FlowWriteLock flowWriteLock
    ) {
        this.flowVersionRepository = flowVersionRepository;
        this.flowStepRepository = flowStepRepository;
        this.flowRepository = flowRepository;
        this.flowService = flowService;
        this.flowStepReferenceValidator = flowStepReferenceValidator;
        this.functionVersionRepository = functionVersionRepository;
        this.flowWriteLock = flowWriteLock;
    }

    public Page<FlowVersion> listVersions(UUID appUserId, UUID flowId, int page, int size) {
        flowService.getFlowById(appUserId, flowId);
        Pageable pageable = PageRequest.of(
                Math.max(page - 1, 0),
                Math.max(size, 1),
                Sort.by(Sort.Direction.DESC, FlowVersion::getVersion)
        );
        return flowVersionRepository.findAllByFlow_Id(flowId, pageable);
    }

    public FlowVersion getVersionById(UUID appUserId, UUID flowId, UUID versionId) {
        flowService.getFlowById(appUserId, flowId);
        return flowVersionRepository.findByIdAndFlow_Id(versionId, flowId)
                .orElseThrow(() -> new ResourceNotFoundException("Flow version not found: " + versionId));
    }

    @Transactional
    public FlowVersion createDraftVersion(UUID appUserId, UUID flowId, FlowVersionCreateRequest request) {
        Flow flow = flowService.getFlowById(appUserId, flowId);
        int nextVersion = flowVersionRepository.findMaxVersion(flowId) + 1;
        String runtime = request.runtime() != null ? request.runtime() : DEFAULT_RUNTIME;

        FlowVersion flowVersion = FlowVersion.create(flow, nextVersion, runtime, request.metadata());
        return flowVersionRepository.save(flowVersion);
    }

    @Transactional
    public FlowVersion adoptVersion(UUID appUserId, UUID flowId, UUID versionId) {
        Flow flow = flowWriteLock.lock(appUserId, flowId);
        FlowVersion flowVersion = getVersionById(appUserId, flowId, versionId);

        if (flowVersion.getStatus() != FlowVersionStatus.DRAFT) {
            throw new IllegalArgumentException("Only a DRAFT version can be adopted, current status: " + flowVersion.getStatus());
        }
        List<FlowStep> steps = flowStepRepository.findAllByFlowVersion_IdOrderByPosition(versionId);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Cannot adopt a flow version with no steps");
        }
        validateStepsForAdoption(appUserId, steps);

        Optional<FlowVersion> currentlyAdopted = flowVersionRepository.findByFlow_IdAndStatus(flowId, FlowVersionStatus.ADOPTED);
        currentlyAdopted.ifPresent(previous -> {
            previous.archive();
            flowVersionRepository.save(previous);
        });

        flowVersion.adopt();
        FlowVersion savedVersion = flowVersionRepository.save(flowVersion);

        flow.activateVersion(savedVersion.getId(), FlowVersionStatus.ADOPTED.name());
        flowRepository.save(flow);

        return savedVersion;
    }

    /**
     * Re-checks every step's component reference (a step's Function/FunctionVersion
     * or referenced Flow/FlowVersion can drift - e.g. soft-deleted - between
     * authoring and adoption), and enforces that positions are strictly
     * ascending and the last step is RESPONSE or SUB_FLOW - the only two
     * component types whose completion can end an invocation. Without this,
     * a flow that runs out of steps without ever completing a RESPONSE
     * leaves its Invocation stuck PENDING forever (see GAP-08). A SUB_FLOW
     * last step is sound by induction: it can only reference an ADOPTED
     * FlowVersion, which was itself already required to end this same way
     * when it was adopted.
     *
     * <p>A FUNCTION last step is also allowed when it references a
     * STATIC-runtime FunctionVersion: the Gateway serves that route by
     * reading the artifact's files directly and never creates an
     * Invocation or dispatches any step for it, so the "ends in RESPONSE"
     * invariant doesn't apply.
     */
    private void validateStepsForAdoption(UUID appUserId, List<FlowStep> steps) {
        int previousPosition = 0;
        for (FlowStep step : steps) {
            if (step.getPosition() <= previousPosition) {
                throw new IllegalArgumentException(
                        "Step positions must be positive and strictly ascending, found out-of-order position "
                                + step.getPosition() + " for step '" + step.getStepKey() + "'");
            }
            previousPosition = step.getPosition();

            flowStepReferenceValidator.validateComponentReference(
                    appUserId, step.getComponentType(), step.getComponentId(), step.getComponentVersionId());
        }

        FlowStep lastStep = steps.get(steps.size() - 1);
        if (!TERMINAL_COMPONENT_TYPES.contains(lastStep.getComponentType()) && !isStaticSiteStep(lastStep)) {
            throw new IllegalArgumentException(
                    "The last step of a Flow must be RESPONSE or SUB_FLOW so the invocation can terminate, found "
                            + lastStep.getComponentType() + " at position " + lastStep.getPosition());
        }
    }

    private boolean isStaticSiteStep(FlowStep step) {
        if (step.getComponentType() != FlowStepComponentType.FUNCTION) {
            return false;
        }
        return functionVersionRepository.findByIdAndFunction_Id(step.getComponentVersionId(), step.getComponentId())
                .map(FunctionVersion::getRuntime)
                .filter(STATIC_RUNTIME::equals)
                .isPresent();
    }

    @Transactional
    public FlowVersion archiveVersion(UUID appUserId, UUID flowId, UUID versionId) {
        Flow flow = flowWriteLock.lock(appUserId, flowId);
        FlowVersion flowVersion = getVersionById(appUserId, flowId, versionId);

        if (flowVersion.getStatus() != FlowVersionStatus.ADOPTED) {
            throw new IllegalArgumentException("Only an ADOPTED version can be archived, current status: " + flowVersion.getStatus());
        }

        flowVersion.archive();
        FlowVersion savedVersion = flowVersionRepository.save(flowVersion);

        flow.clearActiveVersion(savedVersion.getId());
        flowRepository.save(flow);

        return savedVersion;
    }

    @Transactional
    public void deleteDraftVersion(UUID appUserId, UUID flowId, UUID versionId) {
        flowWriteLock.lock(appUserId, flowId);
        FlowVersion flowVersion = getVersionById(appUserId, flowId, versionId);

        if (flowVersion.getStatus() != FlowVersionStatus.DRAFT) {
            throw new IllegalArgumentException("Only a DRAFT version can be deleted, current status: " + flowVersion.getStatus());
        }

        flowStepRepository.deleteAllByFlowVersion_Id(versionId);
        flowVersionRepository.delete(flowVersion);
    }
}
