package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingResult;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** HU-APT-001 registration against the real schema: one transaction writes every scheduling effect or none. */
class SchedulingRegistrationIntegrationTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static JdbcClient jdbc;
    private static JdbcAppointmentRegistration registration;
    private static JdbcAppointmentAvailability availability;

    @BeforeAll
    static void connect() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: integration tests skipped");
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(System.getenv("TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc = JdbcClient.create(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        registration = new JdbcAppointmentRegistration(transactions, jdbc,
                new JdbcAppointmentRepository(jdbc, "America/Bogota"), new JdbcIdempotencyStore(jdbc),
                new JdbcOutboxWriter(jdbc), UUID::randomUUID);
        availability = new JdbcAppointmentAvailability(jdbc);
    }

    @Test
    void newSchedulingCommitsAppointmentReminderAuditOutboxAndKeyTogether() {
        var context = context("key-new-" + UUID.randomUUID());
        Appointment proposed = proposed(UUID.randomUUID(), "2031-04-01T09:00:00-05:00");

        SchedulingResult result = registration.register(context, proposed, () -> { });

        assertTrue(result.created());
        assertEquals(proposed.id(), result.appointment().id());
        Map<String, Object> reminder = jdbc.sql("SELECT appointment_version, due_at, channel, status, attempts "
                + "FROM appointments.appointment_reminders WHERE appointment_id = ?").param(proposed.id()).query()
                .singleRow();
        assertEquals(1, reminder.get("appointment_version"));
        assertEquals(proposed.reminderDueAt().toInstant(), ((Timestamp) reminder.get("due_at")).toInstant());
        assertEquals("EMAIL", reminder.get("channel"));
        assertEquals("PENDING", reminder.get("status"));
        Map<String, Object> audit = jdbc.sql("SELECT operation, to_status, actor_id, actor_role, correlation_id "
                + "FROM appointments.appointment_status_changes WHERE appointment_id = ?").param(proposed.id())
                .query().singleRow();
        assertEquals("SCHEDULE", audit.get("operation"));
        assertEquals("PROGRAMADA", audit.get("to_status"));
        assertEquals(context.actorUserId(), audit.get("actor_id"));
        assertEquals("SECRETARY_ASSISTANT", audit.get("actor_role"));
        assertEquals(context.correlationId(), audit.get("correlation_id"));
        Map<String, Object> event = jdbc.sql("SELECT event_type, aggregate_type, correlation_id, actor_user_id, "
                + "payload->>'appointmentId' AS appointment, payload->>'scheduledBy' AS by, "
                + "payload->>'reminderDueAt' AS due FROM appointments.outbox_events WHERE aggregate_id = ?")
                .param(proposed.id()).query().singleRow();
        assertEquals("AppointmentScheduled", event.get("event_type"));
        assertEquals("Appointment", event.get("aggregate_type"));
        assertEquals(context.correlationId(), event.get("correlation_id"));
        assertEquals(proposed.id().toString(), event.get("appointment"));
        assertEquals(context.actorUserId().toString(), event.get("by"));
        assertEquals(proposed.reminderDueAt().toInstant(), OffsetDateTime.parse((String) event.get("due")).toInstant());
        assertEquals(201, jdbc.sql("SELECT response_status FROM appointments.idempotency_keys WHERE actor_user_id = ? "
                + "AND idempotency_key = ?").params(context.actorUserId(), context.idempotencyKey())
                .query(Integer.class).single());
    }

    @Test
    void retryWithTheSameKeyAndPayloadReturnsTheOriginalWithoutNewEffects() {
        var context = context("key-retry-" + UUID.randomUUID());
        UUID dentist = UUID.randomUUID();
        Appointment first = proposed(dentist, "2031-04-02T09:00:00-05:00");
        registration.register(context, first, () -> { });

        Appointment retry = Appointment.schedule(UUID.randomUUID(), first.patientId(), dentist, first.startAt(),
                first.endAt(), first.reason(), context.actorUserId(), NOW);
        SchedulingResult replay = registration.recover(context, retry).orElseThrow();

        assertFalse(replay.created());
        assertEquals(first.id(), replay.appointment().id());
        SchedulingResult viaRegister = registration.register(context, retry, () -> {
            throw new AssertionError("a replay must not validate again");
        });
        assertEquals(first.id(), viaRegister.appointment().id());
        assertEquals(1, count("appointments", dentist));
    }

    @Test
    void sameKeyWithAnotherPayloadIsAnIdempotencyConflict() {
        var context = context("key-conflict-" + UUID.randomUUID());
        Appointment first = proposed(UUID.randomUUID(), "2031-04-03T09:00:00-05:00");
        registration.register(context, first, () -> { });

        Appointment other = proposed(UUID.randomUUID(), "2031-04-03T11:00:00-05:00");
        assertEquals(SchedulingException.Reason.IDEMPOTENCY_CONFLICT,
                assertThrows(SchedulingException.class, () -> registration.recover(context, other)).reason());
    }

    @Test
    void failedValidationOrOverlapLeavesNoEffectAndNoKey() {
        UUID dentist = UUID.randomUUID();
        var failing = context("key-invalid-" + UUID.randomUUID());
        assertThrows(SchedulingException.class, () -> registration.register(failing,
                proposed(dentist, "2031-04-04T09:00:00-05:00"),
                () -> { throw new SchedulingException(SchedulingException.Reason.PATIENT_INELIGIBLE); }));
        assertTrue(registration.recover(failing, proposed(dentist, "2031-04-04T09:00:00-05:00")).isEmpty());

        registration.register(context("key-first-" + UUID.randomUUID()), proposed(dentist, "2031-04-04T10:00:00-05:00"),
                () -> { });
        var overlapping = context("key-overlap-" + UUID.randomUUID());
        assertEquals(SchedulingException.Reason.SLOT_UNAVAILABLE, assertThrows(SchedulingException.class,
                () -> registration.register(overlapping, proposed(dentist, "2031-04-04T10:15:00-05:00"), () -> { }))
                .reason());
        assertTrue(registration.recover(overlapping, proposed(dentist, "2031-04-04T10:15:00-05:00")).isEmpty());
        assertEquals(1, count("appointments", dentist));
    }

    @Test
    void concurrentRequestsForTheSameSlotCreateExactlyOneAppointment() throws Exception {
        UUID dentist = UUID.randomUUID();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        List<Future<SchedulingResult>> outcomes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Callable<SchedulingResult> attempt = () -> {
                start.await();
                return registration.register(context("key-race-" + UUID.randomUUID()),
                        proposed(dentist, "2031-04-05T09:00:00-05:00"), () -> { });
            };
            outcomes.add(pool.submit(attempt));
        }
        start.countDown();
        int created = 0, rejected = 0;
        for (var outcome : outcomes) {
            try {
                if (outcome.get().created()) created++;
            } catch (ExecutionException failure) {
                assertEquals(SchedulingException.Reason.SLOT_UNAVAILABLE,
                        ((SchedulingException) failure.getCause()).reason());
                rejected++;
            }
        }
        pool.shutdown();
        assertEquals(1, created);
        assertEquals(1, rejected);
        assertEquals(1, count("appointments", dentist));
    }

    @Test
    void availabilityRequiresAnOpenIntervalWithoutBlocksOrActiveAppointments() {
        UUID dentist = UUID.randomUUID();
        UUID schedule = UUID.randomUUID();
        jdbc.sql("INSERT INTO appointments.dentist_schedules VALUES (?, ?, 1, ?, ?, ?, ?)")
                .params(schedule, dentist, Timestamp.from(NOW), dentist, Timestamp.from(NOW), dentist).update();
        jdbc.sql("INSERT INTO appointments.availability_intervals VALUES (?, ?, ?, ?)").params(UUID.randomUUID(),
                schedule, at("2031-04-06T08:00:00-05:00"), at("2031-04-06T12:00:00-05:00")).update();
        jdbc.sql("INSERT INTO appointments.blocked_intervals VALUES (?, ?, ?, ?)").params(UUID.randomUUID(),
                schedule, at("2031-04-06T10:00:00-05:00"), at("2031-04-06T10:30:00-05:00")).update();

        assertTrue(availability.isAvailable(dentist, offset("2031-04-06T08:00:00-05:00"), offset("2031-04-06T08:30:00-05:00")));
        assertFalse(availability.isAvailable(dentist, offset("2031-04-06T11:45:00-05:00"), offset("2031-04-06T12:15:00-05:00")));
        assertFalse(availability.isAvailable(dentist, offset("2031-04-06T10:15:00-05:00"), offset("2031-04-06T10:45:00-05:00")));
        assertFalse(availability.isAvailable(UUID.randomUUID(), offset("2031-04-06T08:00:00-05:00"),
                offset("2031-04-06T08:30:00-05:00")));
        registration.register(context("key-avail-" + UUID.randomUUID()), proposed(dentist, "2031-04-06T09:00:00-05:00"),
                () -> { });
        assertFalse(availability.isAvailable(dentist, offset("2031-04-06T09:15:00-05:00"), offset("2031-04-06T09:45:00-05:00")));
        assertTrue(availability.isAvailable(dentist, offset("2031-04-06T09:30:00-05:00"), offset("2031-04-06T10:00:00-05:00")));
    }

    private static SchedulingContext context(String key) {
        return new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), key, Set.of("SECRETARY_ASSISTANT"),
                UUID.randomUUID());
    }

    private static Appointment proposed(UUID dentist, String start) {
        OffsetDateTime startAt = offset(start);
        return Appointment.schedule(UUID.randomUUID(), UUID.fromString("a9900000-0000-4000-8000-0000000000e1"),
                dentist, startAt, startAt.plusMinutes(30), "Checkup", UUID.randomUUID(), NOW);
    }

    private static OffsetDateTime offset(String value) {
        return OffsetDateTime.parse(value);
    }

    private static Timestamp at(String value) {
        return Timestamp.from(OffsetDateTime.parse(value).toInstant());
    }

    private static int count(String table, UUID dentist) {
        return jdbc.sql("SELECT count(*) FROM appointments." + table + " WHERE dentist_id = ?").param(dentist)
                .query(Integer.class).single();
    }
}
