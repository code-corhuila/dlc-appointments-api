package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.port.out.IdGenerator;
import co.edu.corhuila.dlc.appointments.application.port.out.ReminderRequests;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Due reminders are locked with SKIP LOCKED, so concurrent instances never request the same one. Only PENDING
 * rows of PROGRAMADA/CONFIRMADA appointments whose start is still ahead qualify; late delivery after the start
 * stays an open policy decision and is never attempted.
 */
public final class JdbcReminderRequests implements ReminderRequests {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final JdbcOutboxWriter outbox;
    private final IdGenerator ids;

    public JdbcReminderRequests(TransactionTemplate transactions, JdbcClient jdbc, JdbcOutboxWriter outbox,
            IdGenerator ids) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    private record Due(UUID reminderId, UUID appointmentId, long version, UUID patientId, OffsetDateTime startAt) { }

    @Override
    public int requestDue(Instant now, int limit) {
        Timestamp at = Timestamp.from(now);
        Integer requested = transactions.execute(status -> {
            List<Due> due = jdbc.sql("""
                    SELECT r.reminder_id, r.appointment_id, r.appointment_version, a.patient_id, a.start_at
                    FROM appointments.appointment_reminders r
                    JOIN appointments.appointments a ON a.appointment_id = r.appointment_id
                    WHERE r.status = 'PENDING' AND r.due_at <= ? AND a.start_at > ?
                      AND a.status IN ('PROGRAMADA','CONFIRMADA')
                    ORDER BY r.due_at, r.reminder_id
                    LIMIT ?
                    FOR UPDATE OF r SKIP LOCKED""")
                    .params(at, at, limit)
                    .query((row, n) -> new Due(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                            row.getLong(3), row.getObject(4, UUID.class), row.getObject(5, OffsetDateTime.class)))
                    .list();
            UUID run = ids.newId();
            for (Due reminder : due) {
                jdbc.sql("""
                        UPDATE appointments.appointment_reminders SET status = 'REQUESTED', delivery_id = ?, updated_at = ?
                        WHERE reminder_id = ?""")
                        .params(ids.newId(), at, reminder.reminderId()).update();
                var payload = new LinkedHashMap<String, Object>();
                payload.put("appointmentId", reminder.appointmentId());
                payload.put("patientId", reminder.patientId());
                payload.put("startAt", reminder.startAt().toInstant().toString());
                payload.put("appointmentVersion", reminder.version());
                payload.put("requestedAt", now.toString());
                outbox.append(new OutboxEvent(ids.newId(), "AppointmentReminderRequested", 1, "Appointment",
                        reminder.appointmentId(), json(payload), run, null, null, now));
            }
            return due.size();
        });
        return requested == null ? 0 : requested;
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
