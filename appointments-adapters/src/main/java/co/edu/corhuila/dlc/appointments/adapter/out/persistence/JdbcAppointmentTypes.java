package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentTypes;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reads the migration-owned appointments.appointment_types catalog (HU-APT-006, APT-014). */
public final class JdbcAppointmentTypes implements AppointmentTypes {
    private final JdbcClient jdbc;

    public JdbcAppointmentTypes(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public boolean isActive(String code) {
        return jdbc.sql("SELECT count(*) FROM appointments.appointment_types WHERE code = ? AND active")
                .param(code).query(Long.class).single() == 1;
    }

    @Override
    public List<AppointmentType> listActive() {
        return jdbc.sql("SELECT code, label, color FROM appointments.appointment_types WHERE active "
                + "ORDER BY sort_order, code")
                .query((row, n) -> new AppointmentType(row.getString(1), row.getString(2), row.getString(3))).list();
    }
}
