package com.funchole.backend.controlplane.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional - the Gateway server's own public IPv4 address, shown to users
 * as the A-record target for an apex custom domain (a subdomain instead
 * uses a CNAME to their gateway's own hostname, which needs no IP). Leave
 * unset to support only CNAME (subdomain) custom domains.
 */
@ConfigurationProperties(prefix = "app.gateway-network")
public record GatewayNetworkProperties(
        String publicIp
) {
}
