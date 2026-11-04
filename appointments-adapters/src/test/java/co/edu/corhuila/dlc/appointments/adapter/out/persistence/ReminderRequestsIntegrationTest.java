package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.StaffRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** APT-006 informational reminders: requested once when due, never for invalidated work or after the start. */
class ReminderRequestsIntegrationTest {
    private static JdbcClient jdbc;
    private static JdbcAppointmentRegistration registration;
    private static JdbcAppointmentLifecycleStore lifecycle;
    private static JdbcReminderRequests reminders;

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
        var repository = new JdbcAppointmentRepository(jdbc, "America/Bogota");
        var keys = new JdbcIdempotencyStore(jdbc);
        var outbox = new JdbcOutboxWriter(jdbc);
        registration = new JdbcAppointmentRegistration(transactions, jdbc, repository, keys, outbox, UUID::randomUUID);
        lifecycle = new JdbcAppointmentLifecycleStore(transactions, jdbc, repository, keys, outbox, UUID::randomUUID);
        reminders = new JdbcReminderRequests(transactions, jdbc, new JdbcOutboxWriter(jdbc), UUID::randomUUID);
    }

    @Test
    void aDueReminderIsRequestedOnceWithItsDeliveryIdAndEvent() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Appointment a = schedule(now, now.plus(2, ChronoUnit.HOURS));
        assertTrue(reminders.requestDue(now, 500) >= 1);

        Map<String, Object> row = reminder(a.id());
        assertEquals("REQUESTED", row.get("status"));
        assertNotNull(row.get("delivery_id"));
        Map<String, Object> event = jdbc.sql("SELECT payload->>'patientId' AS patient, payload->>'appointmentVersion' "
                + "AS version, payload->>'startAt' AS start, correlation_id FROM appointments.outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'AppointmentReminderRequested'").param(a.id()).query()
                .singleRow();
        assertEquals(a.patientId().toString(), event.get("patient"));
        assertEquals("1", event.get("version"));
        assertEquals(a.startAt().toInstant(), OffsetDateTime.parse((String) event.get("start")).toInstant());

        reminders.requestDue(now.plusSeconds(1), 500);
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM appointments.outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'AppointmentReminderRequested'").param(a.id()).query(Long.class).single());
    }

    @Test
    void futureInvalidatedAndAlreadyStartedRemindersAreNotRequested() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Appointment future = schedule(now, now.plus(30, ChronoUnit.DAYS));
        Appointment cancelled = schedule(now, now.plus(3, ChronoUnit.HOURS));
        var actor = new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Set.of("ADMINISTRATOR"), UUID.randomUUID());
        lifecycle.apply(new LifecycleCommand.Cancel(cancelled.id(), 1, "Late", true), actor, c -> Optional.of(
                c.cancel("Late", true, StaffRole.ADMINISTRATOR, actor.actorUserId(), now)));
        Appointment started = schedule(now, now.plus(1, ChronoUnit.HOURS));

        reminders.requestDue(now.plus(2, ChronoUnit.HOURS), 500);
        assertEquals("PENDING", reminder(future.id()).get("status"));
        assertEquals("INVALIDATED", reminder(cancelled.id()).get("status"));
        assertEquals("PENDING", reminder(started.id()).get("status"));
    }

    @Test
    void eachRunIsBounded() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        schedule(now, now.plus(4, ChronoUnit.HOURS));
        schedule(now, now.plus(5, ChronoUnit.HOURS));
        assertEquals(1, reminders.requestDue(now, 1));
    }

    private static Appointment schedule(Instant now, Instant start) {
        OffsetDateTime startAt = start.atOffset(ZoneOffset.ofHours(-5));
        Appointment proposed = Appointment.schedule(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), startAt,
                startAt.plusMinutes(30), "Checkup", UUID.randomUUID(), now);
        var context = new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Set.of("SECRETARY_ASSISTANT"), UUID.randomUUID());
        return registration.register(context, proposed, () -> { }).appointment();
    }

    private static Map<String, Object> reminder(UUID appointmentId) {
        return jdbc.sql("SELECT status, delivery_id FROM appointments.appointment_reminders WHERE appointment_id = ? "
                + "ORDER BY appointment_version DESC LIMIT 1").param(appointmentId).query().singleRow();
    }
}
