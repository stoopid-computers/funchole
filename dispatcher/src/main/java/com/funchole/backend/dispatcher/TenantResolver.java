package com.funchole.backend.dispatcher;

import java.util.UUID;

/**
 * Who owns an invocation, so the runtime can keep each tenant's code apart.
 * {@code null} means unknown (older invocations, tests); the runtime then
 * treats the work as belonging to no particular tenant.
 */
public interface TenantResolver {

    UUID resolve(InvocationStepExecution stepExecution);
}
