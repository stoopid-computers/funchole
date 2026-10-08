package com.funchole.backend.dispatcher;

import java.util.UUID;

final class NoopTenantResolver implements TenantResolver {

    @Override
    public UUID resolve(InvocationStepExecution stepExecution) {
        return null;
    }
}
