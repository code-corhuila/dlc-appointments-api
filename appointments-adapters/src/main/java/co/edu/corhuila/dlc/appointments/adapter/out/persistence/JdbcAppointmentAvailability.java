package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentAvailability;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * An interval is available when one of the dentist's availability intervals contains it, no blocked interval
 * overlaps it and no active appointment overlaps it ([) ranges). This read is advisory: the GiST exclusion on
 * appointments remains the atomic APT-002 guard at insert.
 */
public final class JdbcAppointmentAvailability implements AppointmentAvailability {
    private final JdbcClient jdbc;

    public JdbcAppointmentAvailability(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Inside the registration transaction, FOR SHARE holds off a concurrent availability replacement. */
    @Override
    public boolean isAvailable(UUID dentistId, OffsetDateTime startAt, OffsetDateTime endAt) {
        try {
            jdbc.sql("SELECT schedule_id FROM appointments.dentist_schedules WHERE dentist_id = ? FOR SHARE")
                    .param(dentistId).query(UUID.class).optional();
            return jdbc.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM appointments.dentist_schedules s
                        JOIN appointments.availability_intervals a ON a.schedule_id = s.schedule_id
                        WHERE s.dentist_id = :dentist
                          AND a.start_at <= CAST(:start AS timestamptz) AND a.end_at >= CAST(:end AS timestamptz)
                          AND NOT EXISTS (SELECT 1 FROM appointments.blocked_intervals b
                              WHERE b.schedule_id = s.schedule_id
                                AND tstzrange(b.start_at, b.end_at, '[)')
                                    && tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)'))
                          AND NOT EXISTS (SELECT 1 FROM appointments.appointments p
                              WHERE p.dentist_id = :dentist AND p.status IN ('PROGRAMADA','CONFIRMADA','EN_ATENCION')
                                AND tstzrange(p.start_at, p.end_at, '[)')
                                    && tstzrange(CAST(:start AS timestamptz), CAST(:end AS timestamptz), '[)')))""")
                    .param("dentist", dentistId)
                    .param("start", startAt.withOffsetSameInstant(ZoneOffset.UTC))
                    .param("end", endAt.withOffsetSameInstant(ZoneOffset.UTC))
                    .query(Boolean.class).single();
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }
}
