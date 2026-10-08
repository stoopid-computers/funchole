package com.funchole.backend.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where tenant builds run. {@code legacy} (the default) runs them on this host exactly as before;
 * {@code sandbox} sends them to the sandbox manager ({@code SANDBOX_MANAGER_URL} / {@code SANDBOX_MANAGER_TOKEN}).
 */
@ConfigurationProperties(prefix = "app.build-isolation")
public record BuildIsolationProperties(String mode, String managerUrl, String managerToken) {

    public boolean sandboxEnabled() {
        return "sandbox".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }
}
