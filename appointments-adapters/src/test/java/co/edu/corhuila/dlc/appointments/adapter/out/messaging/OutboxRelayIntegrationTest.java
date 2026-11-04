package co.edu.corhuila.dlc.appointments.adapter.out.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcOutboxWriter;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.OutboxEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Outbox relay: canonical envelope after commit, bounded retries with backoff, FAILED after the last attempt. */
class OutboxRelayIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private final List<String[]> published = new ArrayList<>();

    @BeforeAll
    static void connect() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: integration tests skipped");
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(System.getenv("TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    /** The disposable database holds events of other suites; settle them so each case sees only its own. */
    @BeforeEach
    void settleOtherEvents() {
        jdbc.sql("UPDATE appointments.outbox_events SET status = 'PUBLISHED', published_at = now() "
                + "WHERE status <> 'PUBLISHED'").update();
    }

    @Test
    void aPendingEventIsPublishedOnceAsTheCanonicalEnvelope() throws Exception {
        UUID aggregate = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        Instant occurred = Instant.parse("2026-05-01T12:00:00Z");
        UUID event = append("AppointmentConfirmed", aggregate, correlation, null, actor, occurred);
        var relay = relay((key, id, body) -> published.add(new String[]{key, id, body}));

        assertEquals(1, relay.relay(Instant.now(), 10));
        assertEquals(0, relay.relay(Instant.now(), 10));
        assertEquals(1, published.size());
        assertEquals("AppointmentConfirmed", published.get(0)[0]);
        assertEquals(event.toString(), published.get(0)[1]);
        JsonNode envelope = JSON.readTree(published.get(0)[2]);
        assertEquals(event.toString(), envelope.get("eventId").asText());
        assertEquals("AppointmentConfirmed", envelope.get("eventType").asText());
        assertEquals(aggregate.toString(), envelope.get("aggregateId").asText());
        assertEquals("Appointment", envelope.get("aggregateType").asText());
        assertEquals(occurred, Instant.parse(envelope.get("occurredAt").asText()));
        assertEquals(1, envelope.get("version").asInt());
        assertEquals("appointments-service", envelope.get("producer").asText());
        assertEquals("PHONE", envelope.get("payload").get("confirmationChannel").asText());
        assertEquals(correlation.toString(), envelope.get("metadata").get("correlationId").asText());
        assertEquals(correlation.toString(), envelope.get("metadata").get("causationId").asText());
        assertEquals(actor.toString(), envelope.get("metadata").get("actorUserId").asText());
        assertEquals(9, envelope.size());
        assertEquals("PUBLISHED", status(event).get("status"));
    }

    @Test
    void aBrokerFailureIsRetriedLaterAndEndsFailedAfterTheLastAttempt() {
        UUID event = append("AppointmentCancelled", UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                Instant.now());
        var failing = relay((key, id, body) -> { throw new EventPublisher.PublishFailedException(); });
        Instant now = Instant.now();

        assertEquals(0, failing.relay(now, 10));
        Map<String, Object> first = status(event);
        assertEquals("PENDING", first.get("status"));
        assertEquals(1, first.get("attempts"));
        assertEquals("BROKER_UNAVAILABLE", first.get("last_error_code"));
        assertEquals(0, failing.relay(now, 10), "not retried before next_attempt_at");
        assertEquals(1, status(event).get("attempts"));

        for (int attempt = 2; attempt <= JdbcOutboxRelay.MAX_ATTEMPTS; attempt++) {
            failing.relay(now.plus(attempt, ChronoUnit.DAYS), 10);
        }
        Map<String, Object> last = status(event);
        assertEquals("FAILED", last.get("status"));
        assertEquals(JdbcOutboxRelay.MAX_ATTEMPTS, last.get("attempts"));
        assertTrue(relay((key, id, body) -> published.add(new String[]{key})).relay(now.plus(30, ChronoUnit.DAYS), 10)
                == 0, "FAILED events are left for operators");
    }

    @Test
    void eachPassIsBounded() {
        for (int i = 0; i < 3; i++) {
            append("AppointmentNoShow", UUID.randomUUID(), UUID.randomUUID(), null, null, Instant.now());
        }
        assertEquals(2, relay((key, id, body) -> published.add(new String[]{key})).relay(Instant.now(), 2));
    }

    private JdbcOutboxRelay relay(EventPublisher publisher) {
        return new JdbcOutboxRelay(transactions, jdbc, publisher);
    }

    private static UUID append(String type, UUID aggregate, UUID correlation, UUID causation, UUID actor,
                               Instant occurred) {
        UUID event = UUID.randomUUID();
        new JdbcOutboxWriter(jdbc).append(new OutboxEvent(event, type, 1, "Appointment", aggregate,
                "{\"confirmationChannel\":\"PHONE\"}", correlation, causation, actor, occurred));
        return event;
    }

    private static Map<String, Object> status(UUID event) {
        return jdbc.sql("SELECT status, attempts, last_error_code FROM appointments.outbox_events WHERE event_id = ?")
                .param(event).query().singleRow();
    }
}
