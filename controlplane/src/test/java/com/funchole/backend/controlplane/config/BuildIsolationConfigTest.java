package com.funchole.backend.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.funchole.backend.controlplane.functionbuild.process.DefaultProcessExecutor;
import com.funchole.backend.controlplane.functionbuild.process.SandboxProcessExecutor;
import org.junit.jupiter.api.Test;

class BuildIsolationConfigTest {

    private final BuildIsolationConfig config = new BuildIsolationConfig();

    @Test
    void unsetOrLegacyGivesTheOriginalLocalExecutor() {
        assertThat(config.buildProcessExecutor(new BuildIsolationProperties(null, null, null))).isInstanceOf(DefaultProcessExecutor.class);
        assertThat(config.buildProcessExecutor(new BuildIsolationProperties("legacy", "", ""))).isInstanceOf(DefaultProcessExecutor.class);
        assertThat(config.buildProcessExecutor(new BuildIsolationProperties("", "", ""))).isInstanceOf(DefaultProcessExecutor.class);
    }

    @Test
    void sandboxModeUsesTheSandboxExecutor() {
        assertThat(config.buildProcessExecutor(new BuildIsolationProperties("sandbox", "http://sandbox-manager:7090", "secret-token")))
                .isInstanceOf(SandboxProcessExecutor.class);
    }

    @Test
    void sandboxModeWithoutAManagerOrTokenRefusesToStartInsteadOfFallingBackToLocalBuilds() {
        assertThatThrownBy(() -> config.buildProcessExecutor(new BuildIsolationProperties("sandbox", "", "t")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> config.buildProcessExecutor(new BuildIsolationProperties("sandbox", "http://m:7090", "")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownModeFailsFast() {
        assertThatThrownBy(() -> config.buildProcessExecutor(new BuildIsolationProperties("sandbx", "http://m", "t")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("legacy' or 'sandbox");
    }
}
