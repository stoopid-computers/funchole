package com.funchole.backend.controlplane.repository;

import com.funchole.backend.controlplane.entity.Flow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlowRepository extends JpaRepository<Flow, UUID> {

    @EntityGraph(attributePaths = { "gateway" })
    Page<Flow> findAllByAppUser_IdAndDeletedAtIsNull(UUID appUserId, Pageable pageable);

    @EntityGraph(attributePaths = { "gateway" })
    Optional<Flow> findByIdAndAppUser_IdAndDeletedAtIsNull(UUID id, UUID appUserId);

    boolean existsByFlowKey(String flowKey);

    boolean existsByGateway_IdAndDeletedAtIsNull(UUID gatewayId);

    long countByAppUser_IdAndDeletedAtIsNull(UUID appUserId);
}
