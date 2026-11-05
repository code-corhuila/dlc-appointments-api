package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentSnapshot;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.ConfirmationStatus;
import co.edu.corhuila.dlc.appointments.domain.model.ReminderStatus;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * appointments.appointments with an explicit row-to-aggregate mapping. Instants are stored and read in UTC.
 * The GiST exclusion (SQLSTATE 23P01) is the atomic APT-002 guard and surfaces as SLOT_UNAVAILABLE.
 */
public final class JdbcAppointmentRepository {
    private static final String EXCLUSION_VIOLATION = "23P01";
    private final JdbcClient jdbc;
    private final String clinicTimezone;

    public JdbcAppointmentRepository(JdbcClient jdbc, String clinicTimezone) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clinicTimezone = Objects.requireNonNull(clinicTimezone, "clinicTimezone");
    }

    public void insert(Appointment appointment) {
        AppointmentSnapshot a = appointment.snapshot();
        try {
            jdbc.sql("""
                    INSERT INTO appointments.appointments (appointment_id, patient_id, dentist_id, start_at, end_at,
                        clinic_timezone, reason, status, confirmation_status, reminder_due_at, reminder_status,
                        version, created_at, created_by, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(a.id(), a.patientId(), a.dentistId(), utc(a.startAt()), utc(a.endAt()), clinicTimezone,
                            a.reason(), a.status().name(), a.confirmationStatus().name(),
                            utc(appointment.reminderDueAt()), a.reminderStatus().name(), a.version(),
                            Timestamp.from(a.createdAt()), a.createdBy(), Timestamp.from(a.updatedAt()), a.updatedBy())
                    .update();
        } catch (DataIntegrityViolationException violation) {
            if (sqlState(violation).equals(EXCLUSION_VIOLATION)) {
                throw new SchedulingException(SchedulingException.Reason.SLOT_UNAVAILABLE);
            }
            throw violation;
        }
    }

    public Optional<Appointment> findById(UUID id) {
        return jdbc.sql("""
                SELECT appointment_id, patient_id, dentist_id, start_at, end_at, reason, status, confirmation_status,
                       reminder_status, version, created_at, created_by, updated_at, updated_by
                FROM appointments.appointments WHERE appointment_id = ?""")
                .param(id)
                .query((row, n) -> Appointment.reconstitute(snapshot(row)))
                .optional();
    }

    private static AppointmentSnapshot snapshot(ResultSet row) throws SQLException {
        return new AppointmentSnapshot(row.getObject("appointment_id", UUID.class),
                row.getObject("patient_id", UUID.class), row.getObject("dentist_id", UUID.class),
                instant(row, "start_at"), instant(row, "end_at"), row.getString("reason"),
                AppointmentStatus.valueOf(row.getString("status")),
                ConfirmationStatus.valueOf(row.getString("confirmation_status")),
                ReminderStatus.valueOf(row.getString("reminder_status")), row.getLong("version"),
                instant(row, "created_at").toInstant(), row.getObject("created_by", UUID.class),
                instant(row, "updated_at").toInstant(), row.getObject("updated_by", UUID.class));
    }

    private static OffsetDateTime instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static String sqlState(DataIntegrityViolationException violation) {
        Throwable cause = violation.getMostSpecificCause();
        return cause instanceof SQLException sql && sql.getSQLState() != null ? sql.getSQLState() : "";
    }
}
