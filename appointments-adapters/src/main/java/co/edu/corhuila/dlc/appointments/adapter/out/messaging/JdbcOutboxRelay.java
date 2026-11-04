package co.edu.corhuila.dlc.appointments.adapter.out.messaging;

import co.edu.corhuila.dlc.appointments.application.port.out.OutboxRelay;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Due PENDING outbox rows are locked with SKIP LOCKED and published in recording order as the canonical envelope
 * {eventId, eventType, aggregateId, aggregateType, occurredAt, version, producer, payload, metadata}. A broker
 * failure schedules a retry with exponential backoff and jitter; after MAX_ATTEMPTS the row is FAILED with a safe
 * error code for operators. Rows were committed by their business transaction, so publication follows the commit.
 */
public final class JdbcOutboxRelay implements OutboxRelay {
    public static final int MAX_ATTEMPTS = 10;
    private static final String PRODUCER = "appointments-service";
    private static final Duration BASE_DELAY = Duration.ofSeconds(5);
    private static final Duration MAX_DELAY = Duration.ofMinutes(15);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final EventPublisher publisher;

    public JdbcOutboxRelay(TransactionTemplate transactions, JdbcClient jdbc, EventPublisher publisher) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    private record Row(UUID eventId, String type, int version, String aggregateType, UUID aggregateId, String payload,
            UUID correlationId, UUID causationId, UUID actorUserId, OffsetDateTime occurredAt, int attempts) { }

    @Override
    public int relay(Instant now, int limit) {
        Integer published = transactions.execute(status -> {
            List<Row> due = jdbc.sql("""
                    SELECT event_id, event_type, event_version, aggregate_type, aggregate_id, payload::text,
                           correlation_id, causation_id, actor_user_id, occurred_at, attempts
                    FROM appointments.outbox_events
                    WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= ?)
                    ORDER BY recorded_at, event_id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED""")
                    .params(Timestamp.from(now), limit)
                    .query((r, n) -> new Row(r.getObject(1, UUID.class), r.getString(2), r.getInt(3), r.getString(4),
                            r.getObject(5, UUID.class), r.getString(6), r.getObject(7, UUID.class),
                            r.getObject(8, UUID.class), r.getObject(9, UUID.class),
                            r.getObject(10, OffsetDateTime.class), r.getInt(11)))
                    .list();
            int sent = 0;
            for (Row row : due) {
                try {
                    publisher.publish(row.type(), row.eventId().toString(), envelope(row));
                } catch (EventPublisher.PublishFailedException unconfirmed) {
                    retryLater(row, now);
                    break; // The broker is unavailable: leave the rest of the batch for the next pass.
                }
                jdbc.sql("""
                        UPDATE appointments.outbox_events SET status = 'PUBLISHED', published_at = ?, attempts = ?,
                            next_attempt_at = NULL, last_error_code = NULL
                        WHERE event_id = ?""")
                        .params(Timestamp.from(now), row.attempts() + 1, row.eventId()).update();
                sent++;
            }
            return sent;
        });
        return published == null ? 0 : published;
    }

    private void retryLater(Row row, Instant now) {
        int attempts = row.attempts() + 1;
        if (attempts >= MAX_ATTEMPTS) {
            jdbc.sql("""
                    UPDATE appointments.outbox_events SET status = 'FAILED', attempts = ?, next_attempt_at = NULL,
                        last_error_code = 'BROKER_UNAVAILABLE'
                    WHERE event_id = ?""").params(attempts, row.eventId()).update();
            return;
        }
        long exponential = BASE_DELAY.toMillis() << Math.min(attempts - 1, 20);
        long delay = Math.min(exponential, MAX_DELAY.toMillis()) + ThreadLocalRandom.current().nextLong(1000);
        jdbc.sql("""
                UPDATE appointments.outbox_events SET attempts = ?, next_attempt_at = ?, last_error_code = 'BROKER_UNAVAILABLE'
                WHERE event_id = ?""").params(attempts, Timestamp.from(now.plusMillis(delay)), row.eventId()).update();
    }

    /** metadata.causationId is required by the event schemas; a first event is caused by its own correlation. */
    private static String envelope(Row row) {
        try {
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("correlationId", row.correlationId());
            metadata.put("causationId", row.causationId() != null ? row.causationId() : row.correlationId());
            metadata.put("actorUserId", row.actorUserId());
            var envelope = new LinkedHashMap<String, Object>();
            envelope.put("eventId", row.eventId());
            envelope.put("eventType", row.type());
            envelope.put("aggregateId", row.aggregateId());
            envelope.put("aggregateType", row.aggregateType());
            envelope.put("occurredAt", row.occurredAt().toInstant().toString());
            envelope.put("version", row.version());
            envelope.put("producer", PRODUCER);
            envelope.put("payload", JSON.readTree(row.payload()));
            envelope.put("metadata", metadata);
            return JSON.writeValueAsString(envelope);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
