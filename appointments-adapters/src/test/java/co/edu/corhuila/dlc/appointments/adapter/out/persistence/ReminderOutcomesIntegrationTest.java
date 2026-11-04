package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.application.port.out.ReminderOutcomes;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.ReminderOutcomeConflictException;
import co.edu.corhuila.dlc.appointments.domain.model.StaffRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR-005 worker outcomes: recorded once per delivery, never reviving invalidated or obsolete reminders. */
class ReminderOutcomesIntegrationTest {
    private static JdbcClient jdbc;
    private static JdbcAppointmentRegistration registration;
    private static JdbcAppointmentLifecycleStore lifecycle;
    private static JdbcReminderRequests requests;
    private static JdbcReminderOutcomes outcomes;

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
        var outbox = new JdbcOutboxWriter(jdbc);
        registration = new JdbcAppointmentRegistration(transactions, jdbc, repository, new JdbcIdempotencyStore(jdbc),
                outbox, UUID::randomUUID);
        lifecycle = new JdbcAppointmentLifecycleStore(transactions, jdbc, repository, new JdbcIdempotencyStore(jdbc),
                outbox, UUID::randomUUID);
        requests = new JdbcReminderRequests(transactions, jdbc, outbox, UUID::randomUUID);
        outcomes = new JdbcReminderOutcomes(transactions, jdbc, outbox, UUID::randomUUID);
    }

    @Test
    void detailsCarryTheLiveReminderVersionSoAConfirmationDoesNotMakeItObsolete() {
        Appointment a = requested();
        var actor = context();
        lifecycle.apply(new LifecycleCommand.Confirm(a.id(), 1, co.edu.corhuila.dlc.appointments.domain.model
                .ConfirmationChannel.PHONE), actor, c -> Optional.of(c.confirm(co.edu.corhuila.dlc.appointments
                .domain.model.ConfirmationChannel.PHONE, actor.actorUserId(), Instant.now())));

        ReminderOutcomes.Details details = outcomes.details(a.id()).orElseThrow();
        assertEquals(1, details.appointmentVersion());
        assertEquals("CONFIRMADA", details.status());
        assertEquals(a.patientId(), details.patientId());
        assertTrue(outcomes.details(UUID.randomUUID()).isEmpty());
    }

    @Test
    void aSentOutcomeIsRecordedOnceAndPublished() {
        Appointment a = requested();
        UUID delivery = deliveryOf(a);
        var sent = new ReminderOutcomes.Outcome(a.id(), delivery, 1, "SENT", 1, Instant.now(), "provider-1", null);

        assertTrue(outcomes.record(sent));
        assertFalse(outcomes.record(sent));
        assertEquals("SENT|SENT", jdbc.sql("SELECT r.status || '|' || a.reminder_status FROM appointments.appointment_reminders r "
                + "JOIN appointments.appointments a USING (appointment_id) WHERE r.delivery_id = ?").param(delivery)
                .query(String.class).single());
        assertEquals("provider-1 REMINDER 1", jdbc.sql("SELECT concat_ws(' ', payload->>'providerMessageId', "
                + "payload->>'notificationType', payload->>'appointmentVersion') FROM appointments.outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'AppointmentNotificationSent'").param(a.id())
                .query(String.class).single());
        assertThrows(ReminderOutcomeConflictException.class, () -> outcomes.record(new ReminderOutcomes.Outcome(a.id(),
                delivery, 1, "DELIVERY_FAILED", 3, Instant.now(), null, "SMTP_TIMEOUT")));
    }

    @Test
    void aFailedOutcomeIsMirroredAsFailedAndPublishedAsNotRetryable() {
        Appointment a = requested();
        UUID delivery = deliveryOf(a);
        outcomes.record(new ReminderOutcomes.Outcome(a.id(), delivery, 1, "DELIVERY_FAILED", 3, Instant.now(), null,
                "SMTP_TIMEOUT"));
        assertEquals("DELIVERY_FAILED|FAILED|SMTP_TIMEOUT", jdbc.sql("SELECT r.status || '|' || a.reminder_status "
                + "|| '|' || r.outcome_code FROM appointments.appointment_reminders r JOIN appointments.appointments a "
                + "USING (appointment_id) WHERE r.delivery_id = ?").param(delivery).query(String.class).single());
        assertEquals("SMTP_TIMEOUT false", jdbc.sql("SELECT concat_ws(' ', payload->>'failureCode', "
                + "payload->>'retryable') FROM appointments.outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'AppointmentNotificationDeliveryFailed'").param(a.id()).query(String.class).single());
    }

    @Test
    void unknownDeliveriesOtherVersionsAndInvalidatedRemindersAreConflicts() {
        Appointment a = requested();
        UUID delivery = deliveryOf(a);
        assertThrows(ReminderOutcomeConflictException.class, () -> outcomes.record(new ReminderOutcomes.Outcome(a.id(),
                UUID.randomUUID(), 1, "SENT", 1, Instant.now(), "p", null)));
        assertThrows(ReminderOutcomeConflictException.class, () -> outcomes.record(new ReminderOutcomes.Outcome(a.id(),
                delivery, 2, "SENT", 1, Instant.now(), "p", null)));
        assertThrows(ReminderOutcomeConflictException.class, () -> outcomes.record(new ReminderOutcomes.Outcome(
                UUID.randomUUID(), delivery, 1, "SENT", 1, Instant.now(), "p", null)));

        var admin = new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Set.of("ADMINISTRATOR"), UUID.randomUUID());
        lifecycle.apply(new LifecycleCommand.Cancel(a.id(), 1, "Late", true), admin, c -> Optional.of(
                c.cancel("Late", true, StaffRole.ADMINISTRATOR, admin.actorUserId(), Instant.now())));
        assertThrows(ReminderOutcomeConflictException.class, () -> outcomes.record(new ReminderOutcomes.Outcome(a.id(),
                delivery, 1, "SENT", 1, Instant.now(), "p", null)));
        assertEquals("INVALIDATED", jdbc.sql("SELECT status FROM appointments.appointment_reminders WHERE delivery_id = ?")
                .param(delivery).query(String.class).single());
    }

    /** A live appointment a few hours ahead whose reminder was requested by the scheduler. */
    private static Appointment requested() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime start = now.plus(2, ChronoUnit.HOURS).plusSeconds((long) (Math.random() * 100_000))
                .atOffset(ZoneOffset.ofHours(-5));
        Appointment proposed = Appointment.schedule(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), start,
                start.plusMinutes(30), "Checkup", UUID.randomUUID(), now);
        Appointment a = registration.register(context(), proposed, () -> { }).appointment();
        requests.requestDue(now, 1000);
        return a;
    }

    private static UUID deliveryOf(Appointment a) {
        return jdbc.sql("SELECT delivery_id FROM appointments.appointment_reminders WHERE appointment_id = ?")
                .param(a.id()).query(UUID.class).single();
    }

    private static SchedulingContext context() {
        return new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Set.of("SECRETARY_ASSISTANT"), UUID.randomUUID());
    }
}
