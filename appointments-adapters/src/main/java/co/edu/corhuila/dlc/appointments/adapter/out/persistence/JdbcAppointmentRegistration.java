package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingResult;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentRegistration;
import co.edu.corhuila.dlc.appointments.application.port.out.IdGenerator;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HU-APT-001 registration in one transaction: appointment, informational reminder (APT-006), SCHEDULE audit,
 * AppointmentScheduled outbox row and the Idempotency-Key (Norma 5.3.8/5.3.11). A failure commits none of them.
 * The key scope is actor + SCHEDULE_APPOINTMENT + key; the request hash covers the five creation fields.
 */
public final class JdbcAppointmentRegistration implements AppointmentRegistration {
    private static final String OPERATION = "SCHEDULE_APPOINTMENT";
    private static final int MAX_ATTEMPTS = 3;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final JdbcAppointmentRepository appointments;
    private final JdbcIdempotencyStore keys;
    private final JdbcOutboxWriter outbox;
    private final IdGenerator ids;

    public JdbcAppointmentRegistration(TransactionTemplate transactions, JdbcClient jdbc,
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
    public Optional<SchedulingResult> recover(SchedulingContext context, Appointment proposed) {
        try {
            return keys.find(context.actorUserId(), OPERATION, context.idempotencyKey()).map(stored -> {
                if (!stored.requestHash().equals(hash(proposed))) {
                    throw new SchedulingException(SchedulingException.Reason.IDEMPOTENCY_CONFLICT);
                }
                Appointment original = appointments.findById(stored.resourceId()).orElseThrow(
                        () -> new IllegalStateException("Idempotency key references a missing appointment."));
                return new SchedulingResult(original, 1, false);
            });
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * Concurrent inserts that collide on the GiST exclusion can end in a PostgreSQL deadlock (40P01) instead of
     * 23P01. The whole transaction is retried a bounded number of times; the retry sees the committed winner.
     */
    @Override
    public SchedulingResult register(SchedulingContext context, Appointment proposed, Runnable validateNewOperation) {
        for (int attempt = 1; ; attempt++) {
            try {
                return registerOnce(context, proposed, validateNewOperation);
            } catch (PessimisticLockingFailureException contended) {
                if (attempt == MAX_ATTEMPTS) {
                    throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
                }
            }
        }
    }

    private SchedulingResult registerOnce(SchedulingContext context, Appointment proposed,
            Runnable validateNewOperation) {
        try {
            return transactions.execute(status -> recover(context, proposed).orElseGet(() -> {
                new JdbcPatientGates(jdbc).requireOpen(proposed.patientId());
                validateNewOperation.run();
                appointments.insert(proposed);
                writeEffects(context, proposed);
                keys.save(new IdempotencyRecord(context.actorUserId(), OPERATION, context.idempotencyKey(),
                        hash(proposed), "Appointment", proposed.id(), 201, proposed.createdAt()));
                return new SchedulingResult(proposed, 1, true);
            }));
        } catch (DuplicateIdempotencyKeyException concurrentRetry) {
            // The same key committed first in another transaction: answer with that original result.
            return recover(context, proposed).orElseThrow(
                    () -> new SchedulingException(SchedulingException.Reason.IDEMPOTENCY_CONFLICT));
        } catch (PessimisticLockingFailureException contended) {
            throw contended;
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private void writeEffects(SchedulingContext context, Appointment a) {
        Timestamp now = Timestamp.from(a.createdAt());
        jdbc.sql("""
                INSERT INTO appointments.appointment_reminders (reminder_id, appointment_id, appointment_version,
                    due_at, channel, status, attempts, delivery_id, outcome_code, outcome_at, invalidated_at,
                    created_at, updated_at)
                VALUES (?, ?, 1, ?, 'EMAIL', 'PENDING', 0, NULL, NULL, NULL, NULL, ?, ?)""")
                .params(ids.newId(), a.id(), Timestamp.from(a.reminderDueAt().toInstant()), now, now).update();
        jdbc.sql("""
                INSERT INTO appointments.appointment_status_changes (change_id, appointment_id, appointment_version,
                    operation, from_status, to_status, previous_start_at, previous_end_at, new_start_at, new_end_at,
                    reason, override, actor_id, actor_role, correlation_id, occurred_at)
                VALUES (?, ?, 1, 'SCHEDULE', NULL, 'PROGRAMADA', NULL, NULL, NULL, NULL, NULL, false, ?, ?, ?, ?)""")
                .params(ids.newId(), a.id(), context.actorUserId(), auditRole(context), context.correlationId(), now)
                .update();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("appointmentId", a.id());
        payload.put("patientId", a.patientId());
        payload.put("dentistId", a.dentistId());
        payload.put("startAt", a.startAt().toInstant().toString());
        payload.put("endAt", a.endAt().toInstant().toString());
        payload.put("reminderDueAt", a.reminderDueAt().toInstant().toString());
        payload.put("scheduledBy", context.actorUserId());
        outbox.append(new OutboxEvent(ids.newId(), "AppointmentScheduled", 1, "Appointment", a.id(), json(payload),
                context.correlationId(), context.correlationId(), context.actorUserId(), a.createdAt()));
    }

    /** The audit records the strongest staff role that allowed the operation. */
    private static String auditRole(SchedulingContext context) {
        return context.roles().contains("ADMINISTRATOR") ? "ADMINISTRATOR" : "SECRETARY_ASSISTANT";
    }

    private static String hash(Appointment a) {
        String canonical = String.join("\n", a.patientId().toString(), a.dentistId().toString(),
                a.startAt().toInstant().toString(), a.endAt().toInstant().toString(), a.reason());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
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
