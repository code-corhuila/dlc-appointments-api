package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Annex C integration level: real PostgreSQL with the dlc-appointments-db schema, connected as a login that
 * inherits appointments_runtime. Skipped (not failed) when TEST_DATABASE_URL is not set. The runtime cannot
 * delete rows, so every case uses fresh identifiers.
 */
class PostgresPersistenceIntegrationTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private static JdbcAppointmentRepository appointments;
    private static JdbcIdempotencyStore idempotency;
    private static JdbcOutboxWriter outbox;

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
        appointments = new JdbcAppointmentRepository(jdbc, "America/Bogota");
        idempotency = new JdbcIdempotencyStore(jdbc);
        outbox = new JdbcOutboxWriter(jdbc);
    }

    @Test
    void appointmentRoundTripPreservesEveryStoredValueAsUtcInstants() {
        Appointment scheduled = appointment(UUID.randomUUID(), OffsetDateTime.parse("2031-03-03T09:00:00-05:00"));
        appointments.insert(scheduled);

        Appointment stored = appointments.findById(scheduled.id()).orElseThrow();

        var expected = scheduled.snapshot();
        var actual = stored.snapshot();
        assertEquals(expected.startAt().toInstant(), actual.startAt().toInstant());
        assertEquals(expected.endAt().toInstant(), actual.endAt().toInstant());
        assertEquals(ZoneOffset.UTC, actual.startAt().getOffset());
        assertEquals(expected, new co.edu.corhuila.dlc.appointments.domain.model.AppointmentSnapshot(
                actual.id(), actual.patientId(), actual.dentistId(), expected.startAt(), expected.endAt(),
                actual.reason(), actual.status(), actual.confirmationStatus(), actual.reminderStatus(),
                actual.version(), actual.createdAt(), actual.createdBy(), actual.updatedAt(), actual.updatedBy()));
        assertEquals("America/Bogota", jdbc.sql("SELECT clinic_timezone FROM appointments.appointments "
                + "WHERE appointment_id = ?").param(scheduled.id()).query(String.class).single());
    }

    @Test
    void unknownAppointmentIsEmpty() {
        assertTrue(appointments.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void overlappingActiveAppointmentForTheSameDentistIsSlotUnavailable() {
        UUID dentist = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.parse("2031-03-04T09:00:00-05:00");
        appointments.insert(appointment(dentist, start));

        var conflict = assertThrows(SchedulingException.class,
                () -> appointments.insert(appointment(dentist, start.plusMinutes(15))));
        assertEquals(SchedulingException.Reason.SLOT_UNAVAILABLE, conflict.reason());
        appointments.insert(appointment(dentist, start.plusMinutes(30)));
    }

    @Test
    void idempotencyRecordRoundTripAndDuplicateKey() {
        var record = new IdempotencyRecord(UUID.randomUUID(), "SCHEDULE_APPOINTMENT", "retry-key-0001",
                "a".repeat(64), "Appointment", UUID.randomUUID(), 201, NOW);
        idempotency.save(record);

        assertEquals(record, idempotency.find(record.actorUserId(), record.operation(), record.idempotencyKey())
                .orElseThrow());
        assertTrue(idempotency.find(record.actorUserId(), record.operation(), "other-key-0001").isEmpty());
        assertThrows(DuplicateIdempotencyKeyException.class, () -> idempotency.save(record));
    }

    @Test
    void outboxEventIsStoredPendingWithItsJsonPayload() {
        UUID eventId = UUID.randomUUID();
        UUID aggregate = UUID.randomUUID();
        outbox.append(new OutboxEvent(eventId, "AppointmentScheduled", 1, "Appointment", aggregate,
                "{\"appointmentId\":\"" + aggregate + "\"}", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), NOW));

        Map<String, Object> row = jdbc.sql("SELECT status, attempts, payload->>'appointmentId' AS appointment "
                + "FROM appointments.outbox_events WHERE event_id = ?").param(eventId).query().singleRow();
        assertEquals("PENDING", row.get("status"));
        assertEquals(0, row.get("attempts"));
        assertEquals(aggregate.toString(), row.get("appointment"));
    }

    @Test
    void rolledBackTransactionLeavesNeitherTheAppointmentNorItsIdempotencyKey() {
        Appointment scheduled = appointment(UUID.randomUUID(), OffsetDateTime.parse("2031-03-05T09:00:00-05:00"));
        var record = new IdempotencyRecord(scheduled.createdBy(), "SCHEDULE_APPOINTMENT", "rollback-key-01",
                "b".repeat(64), "Appointment", scheduled.id(), 201, NOW);

        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            appointments.insert(scheduled);
            idempotency.save(record);
            throw new IllegalStateException("simulated failure after both writes");
        }));

        assertTrue(appointments.findById(scheduled.id()).isEmpty());
        assertTrue(idempotency.find(record.actorUserId(), record.operation(), record.idempotencyKey()).isEmpty());
    }

    private static Appointment appointment(UUID dentist, OffsetDateTime start) {
        return Appointment.schedule(UUID.randomUUID(), UUID.randomUUID(), dentist, start, start.plusMinutes(30),
                "Checkup", UUID.randomUUID(), NOW);
    }
}
