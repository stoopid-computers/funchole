package com.funchole.backend.controlplane.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Confirms {@code get_function_example}'s four scenarios return exactly
 * {@link FunctionExampleFixtures}'s constants - the same constants the integration/e2e tests
 * listed on that class's own javadoc independently prove are real, working source. This test
 * exercises the tool bean's own scenario-selection logic; MCP transport-level discoverability
 * (auto-registration via {@code @Service} + {@code @McpTool}) is the same mechanism all eleven
 * other MCP tool classes already use, live-verified earlier this session.
 */
@SpringBootTest
@ActiveProfiles("test")
class FunctionExampleMcpToolsTests {

    @Autowired
    private FunctionExampleMcpTools tools;

    @Test
    void returnsTheNodeBasicExample() {
        var response = tools.getFunctionExample("NODE_BASIC");

        assertThat(response.runtime()).isEqualTo("NODE");
        assertThat(response.entrypoint()).isEqualTo(FunctionExampleFixtures.NODE_BASIC_ENTRYPOINT);
        assertThat(response.handler()).isEqualTo(FunctionExampleFixtures.NODE_BASIC_HANDLER);
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).content()).isEqualTo(FunctionExampleFixtures.NODE_BASIC_SOURCE);
    }

    @Test
    void returnsTheNodeDatabaseExample() {
        var response = tools.getFunctionExample("node_database");

        assertThat(response.runtime()).isEqualTo("NODE");
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).content()).isEqualTo(FunctionExampleFixtures.NODE_DATABASE_SOURCE);
        assertThat(response.files().get(0).content()).contains("context.db(");
    }

    @Test
    void returnsTheNodeEnvVarsExample() {
        var response = tools.getFunctionExample("NODE_ENV_VARS");

        assertThat(response.runtime()).isEqualTo("NODE");
        assertThat(response.entrypoint()).isEqualTo(FunctionExampleFixtures.NODE_ENV_VARS_ENTRYPOINT);
        assertThat(response.handler()).isEqualTo(FunctionExampleFixtures.NODE_ENV_VARS_HANDLER);
        assertThat(response.files()).hasSize(1);
        assertThat(response.files().get(0).content()).isEqualTo(FunctionExampleFixtures.NODE_ENV_VARS_SOURCE);
        assertThat(response.files().get(0).content()).contains("process.env");
    }

    @Test
    void returnsTheStaticMultipageExample() {
        var response = tools.getFunctionExample("STATIC_MULTIPAGE");

        assertThat(response.runtime()).isEqualTo("STATIC");
        assertThat(response.entrypoint()).isEqualTo(FunctionExampleFixtures.STATIC_ENTRYPOINT);
        assertThat(response.files()).isEqualTo(FunctionExampleFixtures.staticMultipageFiles());
    }

    @Test
    void rejectsAnUnknownScenario() {
        assertThatThrownBy(() -> tools.getFunctionExample("SOMETHING_ELSE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NODE_BASIC");
    }
}
