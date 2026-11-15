package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.port.out.IdGenerator;
import co.edu.corhuila.dlc.appointments.application.port.out.ReminderOutcomes;
import co.edu.corhuila.dlc.appointments.domain.model.ReminderOutcomeConflictException;
import co.edu.corhuila.dlc.appointments.domain.model.ReminderOutcomeConflictException.Reason;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outcomes lock the reminder by its delivery id. Only a REQUESTED reminder of the reported appointment and version
 * accepts one; it becomes SENT or DELIVERY_FAILED, the appointment row mirrors SENT/FAILED (D7) without a version
 * change, and the matching notification event is written to the outbox in the same transaction.
 */
public final class JdbcReminderOutcomes implements ReminderOutcomes {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final JdbcOutboxWriter outbox;
    private final IdGenerator ids;

    public JdbcReminderOutcomes(TransactionTemplate transactions, JdbcClient jdbc, JdbcOutboxWriter outbox,
            IdGenerator ids) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public Optional<Details> details(UUID appointmentId) {
        try {
            return jdbc.sql("""
                    SELECT a.appointment_id, a.patient_id,
                           coalesce((SELECT r.appointment_version FROM appointments.appointment_reminders r
                                     WHERE r.appointment_id = a.appointment_id
                                     ORDER BY (r.status <> 'INVALIDATED') DESC, r.appointment_version DESC LIMIT 1),
                                    a.version),
                           a.start_at, a.end_at, a.status
                    FROM appointments.appointments a WHERE a.appointment_id = ?""")
                    .param(appointmentId)
                    .query((row, n) -> new Details(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getLong(3), utc(row.getObject(4, OffsetDateTime.class)),
                            utc(row.getObject(5, OffsetDateTime.class)), row.getString(6)))
                    .optional();
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private record Current(UUID appointmentId, long version, String status) { }

    @Override
    public boolean record(Outcome outcome) {
        try {
            return Boolean.TRUE.equals(transactions.execute(tx -> {
                Current current = jdbc.sql("""
                        SELECT appointment_id, appointment_version, status FROM appointments.appointment_reminders
                        WHERE delivery_id = ? FOR UPDATE""")
                        .param(outcome.deliveryId())
                        .query((row, n) -> new Current(row.getObject(1, UUID.class), row.getLong(2), row.getString(3)))
                        .optional()
                        .filter(c -> c.appointmentId().equals(outcome.appointmentId())
                                && c.version() == outcome.appointmentVersion())
                        .orElseThrow(() -> new ReminderOutcomeConflictException(Reason.STALE));
                if (current.status().equals(outcome.status())) return false;
                if (current.status().equals("SENT") || current.status().equals("DELIVERY_FAILED")) {
                    throw new ReminderOutcomeConflictException(Reason.CONFLICTING);
                }
                if (!current.status().equals("REQUESTED")) throw new ReminderOutcomeConflictException(Reason.STALE);
                apply(outcome);
                return true;
            }));
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private void apply(Outcome o) {
        boolean sent = o.status().equals("SENT");
        Timestamp at = Timestamp.from(o.occurredAt());
        jdbc.sql("""
                UPDATE appointments.appointment_reminders
                SET status = ?, attempts = ?, outcome_at = ?, outcome_code = ?, updated_at = ?
                WHERE delivery_id = ?""")
                .params(o.status(), o.attempts(), at, sent ? null : o.failureCode(), Timestamp.from(Instant.now()),
                        o.deliveryId())
                .update();
        jdbc.sql("UPDATE appointments.appointments SET reminder_status = ? WHERE appointment_id = ?")
                .params(sent ? "SENT" : "FAILED", o.appointmentId()).update();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("appointmentId", o.appointmentId());
        payload.put("notificationType", "REMINDER");
        payload.put("appointmentVersion", o.appointmentVersion());
        if (sent) {
            payload.put("providerMessageId", o.providerMessageId());
            payload.put("sentAt", o.occurredAt().toString());
        } else {
            payload.put("failureCode", o.failureCode());
            payload.put("failedAt", o.occurredAt().toString());
            payload.put("retryable", false);
        }
        UUID correlation = ids.newId();
        outbox.append(new OutboxEvent(ids.newId(), sent ? "AppointmentNotificationSent"
                : "AppointmentNotificationDeliveryFailed", 1, "Appointment", o.appointmentId(), json(payload),
                correlation, null, null, o.occurredAt()));
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
