package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import java.sql.Timestamp;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Writes PENDING outbox rows in the caller's transaction; a separate relay publishes them (Norma 5.3.11). */
public final class JdbcOutboxWriter {
    private final JdbcClient jdbc;

    public JdbcOutboxWriter(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public void append(OutboxEvent event) {
        Timestamp occurred = Timestamp.from(event.occurredAt());
        jdbc.sql("""
                INSERT INTO appointments.outbox_events (event_id, event_type, event_version, aggregate_type,
                    aggregate_id, payload, correlation_id, causation_id, actor_user_id, occurred_at, recorded_at,
                    status, attempts, next_attempt_at, published_at, last_error_code)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, 'PENDING', 0, ?, NULL, NULL)""")
                .params(event.eventId(), event.eventType(), event.eventVersion(), event.aggregateType(),
                        event.aggregateId(), event.payloadJson(), event.correlationId(), event.causationId(),
                        event.actorUserId(), occurred, occurred, occurred)
                .update();
    }
}
