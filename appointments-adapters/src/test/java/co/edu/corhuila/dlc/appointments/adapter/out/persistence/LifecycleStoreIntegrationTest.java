package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.ConfirmationChannel;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import co.edu.corhuila.dlc.appointments.domain.model.StaffRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** HU-APT-002 lifecycle store against the real schema and the runtime role's grants. */
class LifecycleStoreIntegrationTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static int slot = (int) (Math.random() * 400_000);
    private static JdbcClient jdbc;
    private static JdbcAppointmentRegistration registration;
    private static JdbcAppointmentLifecycleStore store;

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
        registration = new JdbcAppointmentRegistration(transactions, jdbc, repository, new JdbcIdempotencyStore(jdbc),
                new JdbcOutboxWriter(jdbc), UUID::randomUUID);
        store = new JdbcAppointmentLifecycleStore(transactions, jdbc, repository, new JdbcIdempotencyStore(jdbc),
                new JdbcOutboxWriter(jdbc), UUID::randomUUID);
    }

    @Test
    void confirmationCommitsTheNewVersionAuditOutboxAndKeyTogether() {
        Appointment a = scheduled();
        var context = context("ADMINISTRATOR");
        Appointment confirmed = store.apply(new LifecycleCommand.Confirm(a.id(), 1, ConfirmationChannel.PHONE), context,
                current -> Optional.of(current.confirm(ConfirmationChannel.PHONE, context.actorUserId(), NOW)));

        assertEquals(2, confirmed.version());
        assertEquals(Map.of("status", "CONFIRMADA", "confirmation_status", "CONFIRMED", "version", 2),
                row("SELECT status, confirmation_status, version FROM appointments.appointments WHERE appointment_id = ?",
                        a.id()));
        assertEquals("CONFIRM PROGRAMADA CONFIRMADA ADMINISTRATOR false", row("SELECT concat_ws(' ', operation, "
                + "from_status, to_status, actor_role, override::text) AS v FROM appointments.appointment_status_changes "
                + "WHERE appointment_id = ? AND appointment_version = 2", a.id()).get("v"));
        assertEquals("AppointmentConfirmed PHONE " + context.actorUserId(), row("SELECT concat_ws(' ', event_type, "
                + "payload->>'confirmationChannel', payload->>'recordedBy') AS v FROM appointments.outbox_events "
                + "WHERE aggregate_id = ? AND event_type <> 'AppointmentScheduled'", a.id()).get("v"));
        assertEquals(200, row("SELECT response_status FROM appointments.idempotency_keys WHERE actor_user_id = ? "
                + "AND operation = 'CONFIRM_APPOINTMENT'", context.actorUserId()).get("response_status"));
    }

    @Test
    void theSameKeyReplaysWithoutDecidingAgainAndAnotherPayloadConflicts() {
        Appointment a = scheduled();
        var context = context("SECRETARY_ASSISTANT");
        var decisions = new AtomicInteger();
        var command = new LifecycleCommand.Confirm(a.id(), 1, ConfirmationChannel.EMAIL);
        for (int i = 0; i < 2; i++) {
            assertEquals(2, store.apply(command, context, current -> {
                decisions.incrementAndGet();
                return Optional.of(current.confirm(ConfirmationChannel.EMAIL, context.actorUserId(), NOW));
            }).version());
        }
        assertEquals(1, decisions.get());
        assertEquals(SchedulingException.Reason.IDEMPOTENCY_CONFLICT, assertThrows(SchedulingException.class,
                () -> store.apply(new LifecycleCommand.Confirm(a.id(), 1, ConfirmationChannel.PHONE), context,
                        current -> Optional.empty())).reason());
    }

    @Test
    void cancellationInvalidatesTheLiveReminderAndPublishesTheReasonAndOverride() {
        Appointment a = scheduled();
        var context = context("ADMINISTRATOR");
        store.apply(new LifecycleCommand.Cancel(a.id(), 1, "Travel", false), context, current -> Optional.of(
                current.cancel("Travel", false, StaffRole.ADMINISTRATOR, context.actorUserId(), NOW)));

        assertEquals("INVALIDATED", row("SELECT status FROM appointments.appointment_reminders "
                + "WHERE appointment_id = ?", a.id()).get("status"));
        assertEquals("AppointmentCancelled Travel false " + a.dentistId(), row("SELECT concat_ws(' ', event_type, "
                + "payload->>'reason', payload->>'override', payload->>'dentistId') AS v FROM appointments.outbox_events "
                + "WHERE aggregate_id = ? AND event_type <> 'AppointmentScheduled'", a.id()).get("v"));
        assertEquals("Travel", row("SELECT reason FROM appointments.appointment_status_changes "
                + "WHERE appointment_id = ? AND operation = 'CANCEL'", a.id()).get("reason"));
    }

    @Test
    void anEmptyDecisionChangesNothingAndAFailedDecisionCommitsNothing() {
        Appointment a = scheduled();
        var context = context("SECRETARY_ASSISTANT");
        assertEquals(1, store.apply(new LifecycleCommand.Cancel(a.id(), 9, "Again", false), context,
                current -> Optional.empty()).version());
        var other = context("SECRETARY_ASSISTANT");
        assertThrows(InvalidStatusTransitionException.class, () -> store.apply(
                new LifecycleCommand.StartAttention(a.id(), 1), other, current -> {
                    throw new InvalidStatusTransitionException();
                }));

        assertEquals(1L, row("SELECT count(*) AS n FROM appointments.appointment_status_changes "
                + "WHERE appointment_id = ?", a.id()).get("n"));
        assertEquals(1L, row("SELECT count(*) AS n FROM appointments.outbox_events WHERE aggregate_id = ?", a.id())
                .get("n"));
        assertEquals(0L, row("SELECT count(*) AS n FROM appointments.idempotency_keys WHERE actor_user_id = ?",
                other.actorUserId()).get("n"));
    }

    @Test
    void attentionStartAndNoShowPublishTheirContractPayloads() {
        Appointment a = scheduled();
        var context = context("SECRETARY_ASSISTANT");
        UUID actor = context.actorUserId();
        store.apply(new LifecycleCommand.Confirm(a.id(), 1, ConfirmationChannel.IN_PERSON), context("ADMINISTRATOR"),
                current -> Optional.of(current.confirm(ConfirmationChannel.IN_PERSON, actor, NOW)));
        store.apply(new LifecycleCommand.StartAttention(a.id(), 2), context,
                current -> Optional.of(current.startAttention(actor, NOW)));
        assertEquals(a.patientId().toString(), row("SELECT payload->>'patientId' AS v FROM appointments.outbox_events "
                + "WHERE aggregate_id = ? AND event_type = 'AppointmentAttentionStarted'", a.id()).get("v"));

        Appointment b = scheduled();
        Instant afterEnd = b.endAt().toInstant().plusSeconds(60);
        store.apply(new LifecycleCommand.NoShow(b.id(), 1, "Absent"), context,
                current -> Optional.of(current.markNoShow("Absent", actor, afterEnd)));
        assertEquals("NO_ASISTIO " + actor, row("SELECT concat_ws(' ', a.status, e.payload->>'recordedBy') AS v "
                + "FROM appointments.appointments a JOIN appointments.outbox_events e ON e.aggregate_id = a.appointment_id "
                + "WHERE a.appointment_id = ? AND e.event_type = 'AppointmentNoShow'", b.id()).get("v"));
    }

    @Test
    void reschedulingReplacesTheReminderAndPublishesTheNewInterval() {
        Appointment a = scheduled();
        var context = context("SECRETARY_ASSISTANT");
        OffsetDateTime next = a.startAt().plusDays(400);
        store.apply(new LifecycleCommand.Reschedule(a.id(), 1, next, next.plusMinutes(45), "Agenda", false), context,
                current -> Optional.of(current.reschedule(next, next.plusMinutes(45), "Agenda", false,
                        StaffRole.SECRETARY_ASSISTANT, context.actorUserId(), NOW)));

        assertEquals("1 INVALIDATED|2 PENDING", jdbc.sql("SELECT string_agg(appointment_version || ' ' || status, '|' "
                + "ORDER BY appointment_version) FROM appointments.appointment_reminders WHERE appointment_id = ?")
                .param(a.id()).query(String.class).single());
        assertEquals(next.minusHours(24).toInstant(), jdbc.sql("SELECT due_at FROM appointments.appointment_reminders "
                + "WHERE appointment_id = ? AND appointment_version = 2").param(a.id())
                .query(OffsetDateTime.class).single().toInstant());
        Map<String, Object> event = row("SELECT payload->>'previousStartAt' AS previous, payload->>'newStartAt' AS start, "
                + "payload->>'appointmentVersion' AS version, payload->>'overrideReason' IS NOT NULL AS has_override "
                + "FROM appointments.outbox_events WHERE aggregate_id = ? AND event_type = 'AppointmentRescheduled'", a.id());
        assertEquals(a.startAt().toInstant(), OffsetDateTime.parse((String) event.get("previous")).toInstant());
        assertEquals(next.toInstant(), OffsetDateTime.parse((String) event.get("start")).toInstant());
        assertEquals("2", event.get("version"));
        assertEquals(false, event.get("has_override"));
    }

    /** D10: a delivered reminder is invalidated with its history and the new date gets its own reminder. */
    @Test
    void reschedulingAfterADeliveredReminderStillRemindsTheNewDate() {
        Appointment a = scheduled();
        jdbc.sql("UPDATE appointments.appointment_reminders SET status = 'SENT', delivery_id = ?, outcome_at = ? "
                + "WHERE appointment_id = ?").params(UUID.randomUUID(), java.sql.Timestamp.from(NOW), a.id()).update();
        var context = context("ADMINISTRATOR");
        OffsetDateTime next = a.startAt().plusDays(500);
        store.apply(new LifecycleCommand.Reschedule(a.id(), 1, next, next.plusMinutes(30), "Override", true), context,
                current -> Optional.of(current.reschedule(next, next.plusMinutes(30), "Override", true,
                        StaffRole.ADMINISTRATOR, context.actorUserId(), NOW)));

        assertEquals("1 INVALIDATED true|2 PENDING false", jdbc.sql("SELECT string_agg(appointment_version || ' ' "
                + "|| status || ' ' || (outcome_at IS NOT NULL), '|' ORDER BY appointment_version) "
                + "FROM appointments.appointment_reminders WHERE appointment_id = ?").param(a.id())
                .query(String.class).single());
    }

    @Test
    void anUnknownAppointmentIsNotFound() {
        assertThrows(AppointmentNotFoundException.class, () -> store.apply(new LifecycleCommand.StartAttention(
                UUID.randomUUID(), 1), context("ADMINISTRATOR"), current -> Optional.empty()));
    }

    private static Appointment scheduled() {
        OffsetDateTime start = OffsetDateTime.parse("2034-01-01T08:00:00-05:00").plusMinutes(30L * slot++);
        Appointment proposed = Appointment.schedule(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), start,
                start.plusMinutes(30), "Checkup", UUID.randomUUID(), NOW);
        return registration.register(context("SECRETARY_ASSISTANT"), proposed, () -> { }).appointment();
    }

    private static SchedulingContext context(String role) {
        return new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(), Set.of(role),
                UUID.randomUUID());
    }

    private static Map<String, Object> row(String sql, Object param) {
        return jdbc.sql(sql).param(param).query().singleRow();
    }
}
