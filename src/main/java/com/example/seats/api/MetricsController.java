package com.example.seats.api;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {
    private final JdbcClient db;
    private final PrometheusMeterRegistry registry;

    public MetricsController(JdbcClient db, PrometheusMeterRegistry registry) {
        this.db = db;
        this.registry = registry;
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    String metrics() {
        // All business series share one database snapshot. No stale cache on DB failure.
        var rows = db.sql("""
                WITH seat_counts AS (
                    SELECT show_id, count(*) FILTER (WHERE reservation_id IS NULL) AS available,
                           count(*) FILTER (WHERE reservation_id IS NOT NULL) AS confirmed
                    FROM show_seats GROUP BY show_id
                ), reasons(reason) AS (
                    VALUES ('confirmed'), ('cancelled'), ('seat_taken'), ('per_user_limit'),
                           ('idempotent_replay'), ('idempotency_key_reused')
                ), outcomes AS (
                    SELECT outcome, count(*) AS n FROM request_outcomes GROUP BY outcome
                )
                SELECT 'seat' AS kind, show_id::text AS show, v.state AS label, v.n
                FROM seat_counts CROSS JOIN LATERAL (VALUES
                    ('available', available), ('held', 0::bigint), ('confirmed', confirmed)
                ) AS v(state, n)
                UNION ALL
                SELECT 'outcome', '', reasons.reason, coalesce(outcomes.n, 0)
                FROM reasons LEFT JOIN outcomes ON outcomes.outcome = reasons.reason
                """).query((rs, row) -> new Series(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))).list();
        StringBuilder out = new StringBuilder();
        out.append("# HELP seats Current seat inventory by show and state.\n# TYPE seats gauge\n");
        out.append("# HELP reservations_confirmed_total Committed reservation creations.\n# TYPE reservations_confirmed_total counter\n");
        out.append("# HELP reservations_cancelled_total Committed reservation cancellations.\n# TYPE reservations_cancelled_total counter\n");
        out.append("# HELP reservation_idempotent_replays_total Requests replaying a committed outcome.\n# TYPE reservation_idempotent_replays_total counter\n");
        out.append("# HELP reservation_requests_declined_total Fresh domain declines by reason.\n# TYPE reservation_requests_declined_total counter\n");
        for (var series : rows) {
            String name = series.kind().equals("seat")
                    ? "seats{show_id=\"" + series.show() + "\",state=\"" + series.label() + "\"}"
                    : switch (series.label()) {
                        case "confirmed" -> "reservations_confirmed_total";
                        case "cancelled" -> "reservations_cancelled_total";
                        case "idempotent_replay" -> "reservation_idempotent_replays_total";
                        default -> "reservation_requests_declined_total{reason=\"" + series.label() + "\"}";
                    };
            out.append(name).append(' ').append(series.count()).append('\n');
        }
        return out.append(registry.scrape()).toString();
    }

    private record Series(String kind, String show, String label, long count) {}
}
