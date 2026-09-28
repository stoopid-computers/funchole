package com.funchole.backend.controlplane.constant;

/**
 * Type-safe keys for the limit types this codebase actually enforces (see
 * {@code PackageLimitService}) - the {@code package_limits}/
 * {@code user_package_overrides} tables themselves store {@code limit_key}
 * as a plain string, so an operator can add a row for a key that isn't in
 * this enum yet, ready for whenever enforcement code for it exists.
 */
public enum PackageLimitKey {
    MAX_GATEWAYS,
    MAX_FLOWS,
    MAX_FUNCTIONS,
    MAX_DOMAINS,
    MAX_DATABASES,
    MAX_CUSTOM_DOMAINS
}
