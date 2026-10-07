package com.funchole.backend.controlplane.service;

import com.funchole.backend.controlplane.entity.Flow;
import com.funchole.backend.controlplane.repository.FlowRepository;
import com.funchole.backend.core.base.exception.ResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Serializes writes to a flow and refreshes any entity already in the persistence context. */
@Component
public class FlowWriteLock {
    private final FlowRepository flowRepository;
    private final EntityManager entityManager;

    public FlowWriteLock(FlowRepository flowRepository, EntityManager entityManager) {
        this.flowRepository = flowRepository;
        this.entityManager = entityManager;
    }

    public Flow lock(UUID appUserId, UUID flowId) {
        Flow flow = flowRepository.findByIdAndAppUser_IdAndDeletedAtIsNull(flowId, appUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Flow not found: " + flowId));
        // A second refresh in the same transaction could discard an unflushed activation.
        if (entityManager.getLockMode(flow) != LockModeType.PESSIMISTIC_WRITE) {
            entityManager.refresh(flow, LockModeType.PESSIMISTIC_WRITE);
        }
        if (flow.getDeletedAt() != null || !flow.getAppUser().getId().equals(appUserId)) {
            throw new ResourceNotFoundException("Flow not found: " + flowId);
        }
        return flow;
    }
}
