package com.funchole.backend.controlplane.config;

import com.funchole.backend.controlplane.mcp.McpGuideCatalog;
import io.modelcontextprotocol.server.McpServerFeatures.SyncResourceSpecification;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpGuidanceConfig {
    @Bean
    public List<SyncResourceSpecification> funcholeGuideResources(McpGuideCatalog catalog) {
        return catalog.specifications();
    }
}
