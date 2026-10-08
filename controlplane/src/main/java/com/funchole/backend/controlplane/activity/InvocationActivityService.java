package com.funchole.backend.controlplane.activity;

import com.funchole.backend.controlplane.dto.ActivitySummaryResponse;
import com.funchole.backend.controlplane.dto.InvocationActivityResponse;
import com.funchole.backend.core.base.response.PaginationResponse;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Read-only "recent requests" for the workspace's Activity screen. Everything
 * is scoped to the owner column that migration V31 fills in, and nothing
 * returned includes an invocation's input or result.
 */
@Service
public class InvocationActivityService {

    /** A request failed if the platform failed it or the page answered with a 5xx. */
    private static final String FAILED = "(i.status = 'FAILED' OR " + "(" + httpStatusSql() + ") >= 500)";

    private final NamedParameterJdbcTemplate jdbc;

    public InvocationActivityService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // The HTTP status a function answered with, when its result carries one.
    private static String httpStatusSql() {
        return "CASE WHEN i.result IS NOT NULL AND jsonb_typeof(i.result) = 'object' "
                + "AND (i.result ->> 'status') ~ '^[1-5][0-9]{2}$' "
                + "THEN (i.result ->> 'status')::int END";
    }

    /**
     * @param source {@code GATEWAY} (visitor traffic), {@code TEST} (test runs) or null for both
     */
    public PaginationResponse<InvocationActivityResponse> list(
            UUID appUserId, String source, UUID flowId, int page, int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);

        StringBuilder where = new StringBuilder("WHERE i.app_user_id = :userId");
        MapSqlParameterSource params = new MapSqlParameterSource("userId", appUserId);
        if (source != null && !source.isBlank()) {
            where.append(" AND i.source = :source");
            params.addValue("source", source.toUpperCase());
        }
        if (flowId != null) {
            where.append(" AND i.flow_id = :flowId");
            params.addValue("flowId", flowId);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM invocations i " + where, params, Long.class);
        long totalElements = total == null ? 0 : total;

        params.addValue("limit", safeSize).addValue("offset", (long) (safePage - 1) * safeSize);
        List<InvocationActivityResponse> items = jdbc.query(
                "SELECT i.id, i.flow_id, i.flow_key, f.name AS flow_name, i.status, i.kind, i.source, "
                        + "(" + httpStatusSql() + ") AS http_status, i.created_at, i.completed_at "
                        + "FROM invocations i LEFT JOIN flows f ON f.id = i.flow_id " + where
                        + " ORDER BY i.created_at DESC LIMIT :limit OFFSET :offset",
                params,
                (rs, row) -> {
                    OffsetDateTime created = toOffset(rs.getTimestamp("created_at"));
                    Timestamp completedAt = rs.getTimestamp("completed_at");
                    Long durationMs = completedAt == null ? null : completedAt.getTime() - rs.getTimestamp("created_at").getTime();
                    int httpStatus = rs.getInt("http_status");
                    return new InvocationActivityResponse(
                            rs.getObject("id", UUID.class),
                            rs.getObject("flow_id", UUID.class),
                            rs.getString("flow_key"),
                            rs.getString("flow_name"),
                            rs.getString("status"),
                            rs.getString("kind"),
                            rs.getString("source"),
                            rs.wasNull() ? null : httpStatus,
                            created,
                            durationMs
                    );
                });

        int totalPages = (int) Math.ceil(totalElements / (double) safeSize);
        return new PaginationResponse<>(items, safePage, safeSize, totalElements, totalPages, safePage == 1, safePage >= totalPages);
    }

    /** Visitor traffic only: test runs would make a quiet site look busy. */
    public ActivitySummaryResponse summary(UUID appUserId) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", appUserId);

        Map<String, Object> totals = new HashMap<>(jdbc.queryForMap(
                "SELECT count(*) FILTER (WHERE i.created_at > now() - interval '24 hours') AS requests, "
                        + "count(*) FILTER (WHERE i.created_at > now() - interval '24 hours' AND " + FAILED + ") AS failed, "
                        + "max(i.created_at) AS last_at "
                        + "FROM invocations i WHERE i.app_user_id = :userId AND i.source = 'GATEWAY'",
                params));

        List<ActivitySummaryResponse.PageActivity> pages = jdbc.query(
                "SELECT i.flow_id, max(i.flow_key) AS flow_key, max(f.name) AS flow_name, max(i.created_at) AS last_at, "
                        + "count(*) FILTER (WHERE i.created_at > now() - interval '24 hours') AS requests, "
                        + "count(*) FILTER (WHERE i.created_at > now() - interval '24 hours' AND " + FAILED + ") AS failed "
                        + "FROM invocations i LEFT JOIN flows f ON f.id = i.flow_id "
                        + "WHERE i.app_user_id = :userId AND i.source = 'GATEWAY' AND i.flow_id IS NOT NULL "
                        + "GROUP BY i.flow_id ORDER BY max(i.created_at) DESC LIMIT 50",
                params,
                (rs, row) -> new ActivitySummaryResponse.PageActivity(
                        rs.getObject("flow_id", UUID.class),
                        rs.getString("flow_key"),
                        rs.getString("flow_name"),
                        toOffset(rs.getTimestamp("last_at")),
                        rs.getLong("requests"),
                        rs.getLong("failed")));

        return new ActivitySummaryResponse(
                ((Number) totals.get("requests")).longValue(),
                ((Number) totals.get("failed")).longValue(),
                toOffset((Timestamp) totals.get("last_at")),
                pages
        );
    }

    private static OffsetDateTime toOffset(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
    }
}
