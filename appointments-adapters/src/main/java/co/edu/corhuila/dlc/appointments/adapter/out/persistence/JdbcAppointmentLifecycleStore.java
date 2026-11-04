package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentLifecycleStore;
import co.edu.corhuila.dlc.appointments.application.port.out.IdGenerator;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.LifecycleOperation;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import co.edu.corhuila.dlc.appointments.domain.model.StatusChange;
import co.edu.corhuila.dlc.appointments.domain.model.Transition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HU-APT-002 lifecycle changes, one transaction each: the appointment row is locked, the decision runs on the
 * locked state, and the new version, its audit row, reminder invalidation, outbox event and the Idempotency-Key
 * (scope: actor + operation + key, hash of the whole command) commit together or not at all.
 */
public final class JdbcAppointmentLifecycleStore implements AppointmentLifecycleStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EnumSet<AppointmentStatus> TERMINAL =
            EnumSet.of(AppointmentStatus.CANCELADA, AppointmentStatus.NO_ASISTIO, AppointmentStatus.FINALIZADA);
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final JdbcAppointmentRepository appointments;
    private final JdbcIdempotencyStore keys;
    private final JdbcOutboxWriter outbox;
    private final IdGenerator ids;

    public JdbcAppointmentLifecycleStore(TransactionTemplate transactions, JdbcClient jdbc,
            JdbcAppointmentRepository appointments, JdbcIdempotencyStore keys, JdbcOutboxWriter outbox,
            IdGenerator ids) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.appointments = Objects.requireNonNull(appointments, "appointments");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public Appointment apply(LifecycleCommand command, SchedulingContext context,
                             Function<Appointment, Optional<Transition>> decide) {
        try {
            return transactions.execute(status -> replay(command, context)
                    .orElseGet(() -> applyOnce(command, context, decide)));
        } catch (DuplicateIdempotencyKeyException concurrentRetry) {
            return replay(command, context).orElseThrow(
                    () -> new SchedulingException(SchedulingException.Reason.IDEMPOTENCY_CONFLICT));
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** A known key answers with the appointment's current representation; another payload is a conflict. */
    private Optional<Appointment> replay(LifecycleCommand command, SchedulingContext context) {
        return keys.find(context.actorUserId(), operation(command), context.idempotencyKey()).map(stored -> {
            if (!stored.requestHash().equals(hash(command))) {
                throw new SchedulingException(SchedulingException.Reason.IDEMPOTENCY_CONFLICT);
            }
            return appointments.findById(stored.resourceId()).orElseThrow(
                    () -> new IllegalStateException("Idempotency key references a missing appointment."));
        });
    }

    private Appointment applyOnce(LifecycleCommand command, SchedulingContext context,
                                  Function<Appointment, Optional<Transition>> decide) {
        Appointment current = appointments.findForUpdate(command.appointmentId())
                .orElseThrow(AppointmentNotFoundException::new);
        Optional<Transition> decided = decide.apply(current);
        Appointment result = decided.map(Transition::appointment).orElse(current);
        decided.ifPresent(transition -> {
            appointments.update(transition.appointment(), current.version());
            audit(transition, context);
            invalidateReminders(transition);
            if (transition.change().operation() == LifecycleOperation.RESCHEDULE) {
                replaceReminder(transition.appointment());
            }
            var event = LifecycleEvents.of(command, transition);
            outbox.append(new OutboxEvent(ids.newId(), event.type(), 1, "Appointment", result.id(),
                    json(event.payload()), context.correlationId(), context.correlationId(), context.actorUserId(),
                    result.updatedAt()));
        });
        keys.save(new IdempotencyRecord(context.actorUserId(), operation(command), context.idempotencyKey(),
                hash(command), "Appointment", result.id(), 200, decided.isPresent() ? result.updatedAt() : Instant.now()));
        return result;
    }

    private void audit(Transition transition, SchedulingContext context) {
        Appointment a = transition.appointment();
        StatusChange c = transition.change();
        jdbc.sql("""
                INSERT INTO appointments.appointment_status_changes (change_id, appointment_id, appointment_version,
                    operation, from_status, to_status, previous_start_at, previous_end_at, new_start_at, new_end_at,
                    reason, override, actor_id, actor_role, correlation_id, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(ids.newId(), a.id(), a.version(), c.operation().name(), c.fromStatus().name(),
                        c.toStatus().name(), c.previousStartAt(), c.previousEndAt(), c.newStartAt(), c.newEndAt(),
                        c.reason(), c.override(), context.actorUserId(),
                        context.roles().contains("ADMINISTRATOR") ? "ADMINISTRATOR" : "SECRETARY_ASSISTANT",
                        context.correlationId(), Timestamp.from(a.updatedAt()))
                .update();
    }

    /** APT-010: terminal changes and reschedules invalidate reminder work that has not been delivered yet. */
    private void invalidateReminders(Transition transition) {
        if (!TERMINAL.contains(transition.change().toStatus())
                && transition.change().operation() != LifecycleOperation.RESCHEDULE) {
            return;
        }
        Timestamp now = Timestamp.from(transition.appointment().updatedAt());
        jdbc.sql("""
                UPDATE appointments.appointment_reminders SET status = 'INVALIDATED', invalidated_at = ?, updated_at = ?
                WHERE appointment_id = ? AND status IN ('PENDING','REQUESTED')""")
                .params(now, now, transition.appointment().id()).update();
    }

    /**
     * APT-005: the new version gets its reminder at the new start minus 24 hours. A reminder that was already
     * SENT or DELIVERY_FAILED stays the live row (uk_appointment_reminders_live_appointment_id), so none is added.
     */
    private void replaceReminder(Appointment a) {
        Timestamp now = Timestamp.from(a.updatedAt());
        jdbc.sql("""
                INSERT INTO appointments.appointment_reminders (reminder_id, appointment_id, appointment_version,
                    due_at, channel, status, attempts, delivery_id, outcome_code, outcome_at, invalidated_at,
                    created_at, updated_at)
                SELECT ?, ?, ?, ?, 'EMAIL', 'PENDING', 0, NULL, NULL, NULL, NULL, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM appointments.appointment_reminders
                    WHERE appointment_id = ? AND status <> 'INVALIDATED')""")
                .params(ids.newId(), a.id(), a.version(), Timestamp.from(a.reminderDueAt().toInstant()), now, now,
                        a.id())
                .update();
    }

    private static String operation(LifecycleCommand command) {
        return command.operation().name() + "_APPOINTMENT";
    }

    /** The record form names every field of the command, including the appointment id and expected version. */
    private static String hash(LifecycleCommand command) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(command.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
