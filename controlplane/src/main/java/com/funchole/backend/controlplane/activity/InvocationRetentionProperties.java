package com.funchole.backend.controlplane.activity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long invocation history is kept. {@code days} of 0 (the default) keeps
 * it forever, as before; set INVOCATION_RETENTION_DAYS to purge older
 * requests, their step records and logs nightly.
 */
@ConfigurationProperties(prefix = "app.invocation-retention")
public record InvocationRetentionProperties(int days) {
}
