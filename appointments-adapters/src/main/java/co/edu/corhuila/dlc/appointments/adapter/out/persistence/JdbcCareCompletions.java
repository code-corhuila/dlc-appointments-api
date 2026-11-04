package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.port.out.CareCompletions;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** DB-09 care_completions, written by the ClinicalCareCompleted consumer; read inside the completion transaction. */
public final class JdbcCareCompletions implements CareCompletions {
    private final JdbcClient jdbc;

    public JdbcCareCompletions(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<Declaration> find(UUID appointmentId) {
        return jdbc.sql("SELECT patient_id, dentist_id FROM appointments.care_completions WHERE appointment_id = ?")
                .param(appointmentId)
                .query((row, n) -> new Declaration(row.getObject(1, UUID.class), row.getObject(2, UUID.class)))
                .optional();
    }
}
