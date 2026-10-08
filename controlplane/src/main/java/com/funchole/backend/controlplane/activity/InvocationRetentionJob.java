package com.funchole.backend.controlplane.activity;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Nightly purge of invocation history past the configured retention. Off by default. */
@Component
public class InvocationRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(InvocationRetentionJob.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final InvocationRetentionProperties properties;

    public InvocationRetentionJob(NamedParameterJdbcTemplate jdbc, InvocationRetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeScheduled() {
        purge(OffsetDateTime.now());
    }

    /** Deletes everything older than the retention window; returns invocations removed. */
    @Transactional
    public int purge(OffsetDateTime now) {
        if (properties.days() <= 0) {
            return 0;
        }
        MapSqlParameterSource cutoff = new MapSqlParameterSource("cutoff", now.minusDays(properties.days()));
        // Step logs cascade from their step executions.
        jdbc.update("DELETE FROM invocation_step_executions WHERE invocation_id IN "
                + "(SELECT id FROM invocations WHERE created_at < :cutoff)", cutoff);
        int removed = jdbc.update("DELETE FROM invocations WHERE created_at < :cutoff", cutoff);
        if (removed > 0) {
            log.info("Invocation retention: removed {} invocation(s) older than {} day(s)", removed, properties.days());
        }
        return removed;
    }
}
