package com.funchole.backend.controlplane.dto;

import jakarta.validation.Valid;
import java.util.UUID;

/** A null expectation means that the flow must have no active version. */
public record FlowPublishRequest(UUID expectedActiveVersionId, @Valid FlowUpdateRequest route) {
}
