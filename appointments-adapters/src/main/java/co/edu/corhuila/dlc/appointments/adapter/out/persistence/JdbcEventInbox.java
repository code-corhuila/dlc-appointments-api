package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.model.InboundEvent;
import co.edu.corhuila.dlc.appointments.application.model.InboundEvent.CareCompletionDeclared;
import co.edu.corhuila.dlc.appointments.application.model.InboundEvent.PatientDeactivation;
import co.edu.corhuila.dlc.appointments.application.port.out.EventInbox;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * DB-08 inbox with the D8 declaration and the D9 gate effect. The inbox row is claimed first (a concurrent
 * delivery of the same id waits on the key), the effect runs, and the row ends PROCESSED or FAILED in the same
 * transaction. Only identifiers and safe codes are stored; the payload is never copied.
 */
public final class JdbcEventInbox implements EventInbox {
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    public JdbcEventInbox(TransactionTemplate transactions, JdbcClient jdbc) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Outcome consume(InboundEvent event, Instant receivedAt) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(receivedAt, "receivedAt");
        Result result = transactions.execute(status -> {
            Timestamp at = Timestamp.from(receivedAt);
            jdbc.sql("""
                    INSERT INTO appointments.inbox_events (event_id, event_type, event_version, producer,
                        aggregate_id, correlation_id, received_at, processed_at, status, last_error_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'FAILED', NULL) ON CONFLICT (event_id) DO NOTHING""")
                    .params(event.eventId(), event.eventType(), event.version(), event.producer(),
                            event.aggregateId(), event.correlationId(), at).update();
            String previous = jdbc.sql("SELECT status FROM appointments.inbox_events WHERE event_id = ? FOR UPDATE")
                    .param(event.eventId()).query(String.class).single();
            if ("PROCESSED".equals(previous)) return new Result(Outcome.DUPLICATE, null);
            String rejection = switch (event.fact()) {
                case CareCompletionDeclared declared -> declare(event.eventId(), declared, at);
                case PatientDeactivation deactivation -> deactivate(deactivation, at);
            };
            jdbc.sql("""
                    UPDATE appointments.inbox_events SET status = ?, processed_at = ?, last_error_code = ?
                    WHERE event_id = ?""")
                    .params(rejection == null ? "PROCESSED" : "FAILED", rejection == null ? at : null, rejection,
                            event.eventId()).update();
            return new Result(Outcome.PROCESSED, rejection);
        });
        if (result.rejection() != null) throw new RejectedEventException(result.rejection());
        return result.outcome();
    }

    /** D8: one declaration per appointment; an equivalent one under another event id is accepted without effect. */
    private String declare(UUID eventId, CareCompletionDeclared d, Timestamp at) {
        boolean known = jdbc.sql("SELECT count(*) FROM appointments.appointments WHERE appointment_id = ?")
                .param(d.appointmentId()).query(Long.class).single() == 1L;
        if (!known) return "UNKNOWN_APPOINTMENT";
        int inserted = jdbc.sql("""
                INSERT INTO appointments.care_completions (appointment_id, event_id, clinical_record_id,
                    consultation_id, patient_id, dentist_id, completed_at, clinical_version, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (appointment_id) DO NOTHING""")
                .params(d.appointmentId(), eventId, d.clinicalRecordId(), d.consultationId(), d.patientId(),
                        d.dentistId(), Timestamp.from(d.completedAt()), d.clinicalVersion(), at).update();
        if (inserted == 1) return null;
        long same = jdbc.sql("""
                SELECT count(*) FROM appointments.care_completions WHERE appointment_id = ?
                  AND clinical_record_id = ? AND consultation_id = ? AND patient_id = ? AND dentist_id = ?
                  AND completed_at = ? AND clinical_version = ?""")
                .params(d.appointmentId(), d.clinicalRecordId(), d.consultationId(), d.patientId(), d.dentistId(),
                        Timestamp.from(d.completedAt()), d.clinicalVersion()).query(Long.class).single();
        return same == 1L ? null : "CONFLICTING_DECLARATION";
    }

    /** D9: PatientDeactivated sets the gate INACTIVE; a gate already INACTIVE is left unchanged. */
    private String deactivate(PatientDeactivation d, Timestamp at) {
        jdbc.sql("""
                INSERT INTO appointments.patient_gates (patient_id, status, version, updated_at)
                VALUES (?, 'INACTIVE', 1, ?)
                ON CONFLICT (patient_id) DO UPDATE SET status = 'INACTIVE',
                    version = appointments.patient_gates.version + 1, updated_at = EXCLUDED.updated_at
                WHERE appointments.patient_gates.status <> 'INACTIVE'""")
                .params(d.patientId(), at).update();
        return null;
    }

    private record Result(Outcome outcome, String rejection) { }
}
