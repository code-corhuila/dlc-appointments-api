package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.model.AppointmentPage;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentReader;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentSearch;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * appointments.appointments with an explicit row-to-aggregate mapping. Instants are stored and read in UTC.
 * The GiST exclusion (SQLSTATE 23P01) is the atomic APT-002 guard and surfaces as SLOT_UNAVAILABLE.
 */
public final class JdbcAppointmentRepository implements AppointmentReader, AppointmentSearch {
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
                        version, created_at, created_by, updated_at, updated_by, type_code)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(a.id(), a.patientId(), a.dentistId(), utc(a.startAt()), utc(a.endAt()), clinicTimezone,
                            a.reason(), a.status().name(), a.confirmationStatus().name(),
                            utc(appointment.reminderDueAt()), a.reminderStatus().name(), a.version(),
                            Timestamp.from(a.createdAt()), a.createdBy(), Timestamp.from(a.updatedAt()), a.updatedBy(),
                            a.typeCode())
                    .update();
        } catch (DataIntegrityViolationException violation) {
            if (sqlState(violation).equals(EXCLUSION_VIOLATION)) {
                throw new SchedulingException(SchedulingException.Reason.SLOT_UNAVAILABLE);
            }
            throw violation;
        }
    }

    @Override
    public Optional<Appointment> findById(UUID id) {
        return jdbc.sql("""
                SELECT appointment_id, patient_id, dentist_id, start_at, end_at, reason, status, confirmation_status,
                       reminder_status, version, created_at, created_by, updated_at, updated_by, type_code
                FROM appointments.appointments WHERE appointment_id = ?""")
                .param(id)
                .query((row, n) -> Appointment.reconstitute(snapshot(row)))
                .optional();
    }

    /** Filters on startAt (from inclusive, to exclusive); most recent first with id as the stable tie-break. */
    @Override
    public AppointmentPage search(Criteria criteria) {
        StringBuilder where = new StringBuilder(" WHERE status IN (:statuses)");
        Map<String, Object> params = new HashMap<>();
        params.put("statuses", criteria.statuses().stream().map(Enum::name).toList());
        if (criteria.patientId() != null) {
            where.append(" AND patient_id = :patient");
            params.put("patient", criteria.patientId());
        }
        if (criteria.dentistId() != null) {
            where.append(" AND dentist_id = :dentist");
            params.put("dentist", criteria.dentistId());
        }
        if (criteria.from() != null) {
            where.append(" AND start_at >= CAST(:from AS timestamptz)");
            params.put("from", utc(criteria.from()));
        }
        if (criteria.to() != null) {
            where.append(" AND start_at < CAST(:to AS timestamptz)");
            params.put("to", utc(criteria.to()));
        }
        long total = jdbc.sql("SELECT count(*) FROM appointments.appointments" + where).params(params)
                .query(Long.class).single();
        params.put("limit", criteria.limit());
        params.put("offset", (long) (criteria.page() - 1) * criteria.limit());
        var items = jdbc.sql("""
                SELECT appointment_id, patient_id, dentist_id, start_at, end_at, reason, status, confirmation_status,
                       reminder_status, version, created_at, created_by, updated_at, updated_by, type_code
                FROM appointments.appointments""" + where
                + " ORDER BY created_at DESC, appointment_id DESC LIMIT :limit OFFSET :offset")
                .params(params).query((row, n) -> Appointment.reconstitute(snapshot(row))).list();
        return new AppointmentPage(items, criteria.page(), criteria.limit(), total);
    }

    /** Locks the row for the rest of the caller's transaction (HU-APT-002 lifecycle changes). */
    public Optional<Appointment> findForUpdate(UUID id) {
        return jdbc.sql("""
                SELECT appointment_id, patient_id, dentist_id, start_at, end_at, reason, status, confirmation_status,
                       reminder_status, version, created_at, created_by, updated_at, updated_by, type_code
                FROM appointments.appointments WHERE appointment_id = ? FOR UPDATE""")
                .param(id)
                .query((row, n) -> Appointment.reconstitute(snapshot(row)))
                .optional();
    }

    /** Writes the next version over the expected one; the GiST exclusion still guards a moved interval. */
    public void update(Appointment next, long expectedVersion) {
        AppointmentSnapshot a = next.snapshot();
        try {
            int updated = jdbc.sql("""
                    UPDATE appointments.appointments SET start_at = ?, end_at = ?, status = ?, confirmation_status = ?,
                        reminder_due_at = ?, reminder_status = ?, version = ?, updated_at = ?, updated_by = ?
                    WHERE appointment_id = ? AND version = ?""")
                    .params(utc(a.startAt()), utc(a.endAt()), a.status().name(), a.confirmationStatus().name(),
                            utc(next.reminderDueAt()), a.reminderStatus().name(), a.version(),
                            Timestamp.from(a.updatedAt()), a.updatedBy(), a.id(), expectedVersion)
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("The locked appointment version changed.");
            }
        } catch (DataIntegrityViolationException violation) {
            if (sqlState(violation).equals(EXCLUSION_VIOLATION)) {
                throw new SchedulingException(SchedulingException.Reason.SLOT_UNAVAILABLE);
            }
            throw violation;
        }
    }

    private static AppointmentSnapshot snapshot(ResultSet row) throws SQLException {
        return new AppointmentSnapshot(row.getObject("appointment_id", UUID.class),
                row.getObject("patient_id", UUID.class), row.getObject("dentist_id", UUID.class),
                instant(row, "start_at"), instant(row, "end_at"), row.getString("reason"),
                AppointmentStatus.valueOf(row.getString("status")),
                ConfirmationStatus.valueOf(row.getString("confirmation_status")),
                ReminderStatus.valueOf(row.getString("reminder_status")), row.getLong("version"),
                instant(row, "created_at").toInstant(), row.getObject("created_by", UUID.class),
                instant(row, "updated_at").toInstant(), row.getObject("updated_by", UUID.class), row.getString("type_code"));
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
