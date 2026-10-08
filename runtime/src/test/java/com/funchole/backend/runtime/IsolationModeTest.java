package com.funchole.backend.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class IsolationModeTest {

    @Test
    void defaultsToLegacyWhenUnset() {
        assertThat(IsolationMode.parseDefault(null)).isEqualTo(IsolationMode.LEGACY);
        assertThat(IsolationMode.parseDefault("  ")).isEqualTo(IsolationMode.LEGACY);
    }

    @Test
    void unknownValueFailsFastInsteadOfFallingBack() {
        assertThatThrownBy(() -> IsolationMode.parseDefault("sandbx")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowListedTenantsGetTheSandboxWhileOthersKeepTheDefault() {
        Set<String> allow = IsolationMode.parseAllowList(" a1 , b2 ,,");
        assertThat(allow).containsExactlyInAnyOrder("a1", "b2");
        assertThat(IsolationMode.resolve(IsolationMode.LEGACY, allow, "a1")).isEqualTo(IsolationMode.SANDBOX);
        assertThat(IsolationMode.resolve(IsolationMode.LEGACY, allow, "zz")).isEqualTo(IsolationMode.LEGACY);
        assertThat(IsolationMode.resolve(IsolationMode.LEGACY, allow, null)).isEqualTo(IsolationMode.LEGACY);
        assertThat(IsolationMode.resolve(IsolationMode.SANDBOX, Set.of(), "zz")).isEqualTo(IsolationMode.SANDBOX);
    }

    @Test
    void mixingLegacyWithASandboxAllowListIsRefusedUnlessExplicitlyAllowed() {
        assertThatThrownBy(() -> IsolationMode.validate(IsolationMode.LEGACY, Set.of("a1"), false))
                .isInstanceOf(IllegalStateException.class);
        IsolationMode.validate(IsolationMode.LEGACY, Set.of("a1"), true);
        IsolationMode.validate(IsolationMode.LEGACY, Set.of(), false);
        IsolationMode.validate(IsolationMode.SANDBOX, Set.of("a1"), false);
        IsolationMode.validate(IsolationMode.SANDBOX, Set.of(), false);
    }
}
