package com.funchole.backend.controlplane.config;

import com.funchole.backend.controlplane.functionbuild.process.DefaultProcessExecutor;
import com.funchole.backend.controlplane.functionbuild.process.ProcessExecutor;
import com.funchole.backend.controlplane.functionbuild.process.SandboxProcessExecutor;
import com.funchole.backend.sandbox.protocol.SandboxManagerClient;
import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one {@link ProcessExecutor} bean: unset or {@code legacy} is the original local executor, unchanged.
 * Deliberately not {@code @Primary}, so a test (or deployment) can still supply its own.
 */
@Configuration
class BuildIsolationConfig {

    @Bean
    ProcessExecutor buildProcessExecutor(BuildIsolationProperties properties) {
        String mode = properties.mode() == null ? "legacy" : properties.mode().trim().toLowerCase();
        if (mode.isEmpty() || mode.equals("legacy")) {
            return new DefaultProcessExecutor();
        }
        if (!properties.sandboxEnabled()) {
            throw new IllegalStateException("BUILD_ISOLATION must be 'legacy' or 'sandbox', got: " + properties.mode());
        }
        if (properties.managerUrl() == null || properties.managerUrl().isBlank()
                || properties.managerToken() == null || properties.managerToken().isBlank()) {
            throw new IllegalStateException("BUILD_ISOLATION=sandbox needs SANDBOX_MANAGER_URL and SANDBOX_MANAGER_TOKEN");
        }
        return new SandboxProcessExecutor(new SandboxManagerClient(URI.create(properties.managerUrl()), properties.managerToken()));
    }
}
