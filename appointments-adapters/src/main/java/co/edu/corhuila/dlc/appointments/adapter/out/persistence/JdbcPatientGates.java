package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * D9 / ADR-017 gate check, run inside the caller's transaction. The row is created OPEN when missing (a concurrent
 * insert waits on the same key) and read FOR SHARE, so a deactivation guard, which locks it FOR UPDATE, cannot
 * commit between this check and the caller's commit. A gate that is not OPEN is PATIENT_INELIGIBLE (409).
 */
final class JdbcPatientGates {
    private final JdbcClient jdbc;

    JdbcPatientGates(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    void requireOpen(UUID patientId) {
        jdbc.sql("""
                INSERT INTO appointments.patient_gates (patient_id, status, version, updated_at)
                VALUES (?, 'OPEN', 1, now()) ON CONFLICT (patient_id) DO NOTHING""")
                .param(patientId).update();
        String status = jdbc.sql("SELECT status FROM appointments.patient_gates WHERE patient_id = ? FOR SHARE")
                .param(patientId).query(String.class).single();
        if (!"OPEN".equals(status)) {
            throw new SchedulingException(SchedulingException.Reason.PATIENT_INELIGIBLE);
        }
    }
}
