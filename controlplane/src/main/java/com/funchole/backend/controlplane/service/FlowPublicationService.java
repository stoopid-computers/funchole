package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.dto.FlowUpdateRequest;
import com.funchole.backend.controlplane.entity.Flow;
import com.funchole.backend.controlplane.entity.FlowVersion;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes one draft and optional route change in a single database transaction. */
@Service
public class FlowPublicationService {
    private final FlowWriteLock flowWriteLock;
    private final FlowVersionService flowVersionService;
    private final FlowService flowService;

    public FlowPublicationService(FlowWriteLock flowWriteLock, FlowVersionService flowVersionService,
                                  FlowService flowService) {
        this.flowWriteLock = flowWriteLock;
        this.flowVersionService = flowVersionService;
        this.flowService = flowService;
    }

    @Transactional
    public FlowVersion publish(UUID appUserId, UUID flowId, UUID draftId, UUID expectedActiveVersionId,
                               FlowUpdateRequest routeUpdate) {
        Flow flow = flowWriteLock.lock(appUserId, flowId);
        return publishLocked(appUserId, flow, draftId, expectedActiveVersionId, routeUpdate);
    }

    /** Keeps the existing REST adopt operation unconditional while sharing the publication lock. */
    @Transactional
    public FlowVersion adoptWithoutExpectation(UUID appUserId, UUID flowId, UUID draftId) {
        Flow flow = flowWriteLock.lock(appUserId, flowId);
        return publishLocked(appUserId, flow, draftId, flow.getActiveFlowVersionId(), null);
    }

    /** Builds a route-only change from the locked Flow so unrelated fields stay current. */
    @Transactional
    public FlowVersion publishRoute(UUID appUserId, UUID flowId, UUID draftId, UUID expectedActiveVersionId,
                                    UUID gatewayId, String httpMethod, String path, Integer priority) {
        Flow flow = flowWriteLock.lock(appUserId, flowId);
        FlowUpdateRequest routeUpdate = new FlowUpdateRequest(flow.getName(), flow.getDescription(), gatewayId,
                httpMethod, path, priority == null ? flow.getPriority() : priority);
        return publishLocked(appUserId, flow, draftId, expectedActiveVersionId, routeUpdate);
    }

    private FlowVersion publishLocked(UUID appUserId, Flow flow, UUID draftId, UUID expectedActiveVersionId,
                                      FlowUpdateRequest routeUpdate) {
        if (!Objects.equals(flow.getActiveFlowVersionId(), expectedActiveVersionId)) {
            throw new FlowPublicationConflictException(flow.getActiveFlowVersionId());
        }
        FlowVersion published = flowVersionService.adoptVersion(appUserId, flow.getId(), draftId);
        if (routeUpdate != null) {
            flowService.updateFlow(appUserId, flow.getId(), routeUpdate);
        }
        return published;
    }
}
