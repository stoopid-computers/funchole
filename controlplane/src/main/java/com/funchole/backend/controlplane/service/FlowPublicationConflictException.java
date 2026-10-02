package com.funchole.backend.controlplane.service;

import java.util.UUID;

/** The active version differs from the version supplied by a publisher. */
public class FlowPublicationConflictException extends IllegalStateException {
    private final UUID actualVersionId;

    public FlowPublicationConflictException(UUID actualVersionId) {
        super("The active revision changed. Read current state before publishing again.");
        this.actualVersionId = actualVersionId;
    }

    public UUID getActualVersionId() {
        return actualVersionId;
    }
}
