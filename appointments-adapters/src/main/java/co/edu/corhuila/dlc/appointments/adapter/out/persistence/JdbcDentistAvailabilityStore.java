package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.port.out.DentistAvailabilityStore;
import co.edu.corhuila.dlc.appointments.application.port.out.IdGenerator;
import co.edu.corhuila.dlc.appointments.domain.model.DentistAvailability;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import co.edu.corhuila.dlc.appointments.domain.model.StaleVersionException;
import co.edu.corhuila.dlc.appointments.domain.model.TimeInterval;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * dentist_schedules with its availability and blocked intervals. A replacement locks the schedule row
 * (FOR UPDATE), reads the dentist's active appointments, applies the domain change and rewrites every interval
 * in one transaction; scheduling takes the same row FOR SHARE, so a booking cannot slip between check and commit.
 */
public final class JdbcDentistAvailabilityStore implements DentistAvailabilityStore {
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final IdGenerator ids;

    public JdbcDentistAvailabilityStore(TransactionTemplate transactions, JdbcClient jdbc, IdGenerator ids) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public Optional<DentistAvailability> find(UUID dentistId) {
        return load(dentistId, "");
    }

    @Override
    public DentistAvailability replace(UUID dentistId, Change change) {
        try {
            return transactions.execute(status -> {
                Optional<DentistAvailability> current = load(dentistId, " FOR UPDATE");
                DentistAvailability next = change.apply(current, activeAppointments(dentistId));
                if (current.isEmpty()) {
                    jdbc.sql("INSERT INTO appointments.dentist_schedules VALUES (?, ?, ?, ?, ?, ?, ?)")
                            .params(next.id(), dentistId, next.version(), Timestamp.from(next.createdAt()),
                                    next.createdBy(), Timestamp.from(next.updatedAt()), next.updatedBy()).update();
                } else {
                    jdbc.sql("""
                            UPDATE appointments.dentist_schedules SET version = ?, updated_at = ?, updated_by = ?
                            WHERE schedule_id = ? AND version = ?""")
                            .params(next.version(), Timestamp.from(next.updatedAt()), next.updatedBy(), next.id(),
                                    current.get().version()).update();
                    jdbc.sql("DELETE FROM appointments.availability_intervals WHERE schedule_id = ?")
                            .param(next.id()).update();
                    jdbc.sql("DELETE FROM appointments.blocked_intervals WHERE schedule_id = ?")
                            .param(next.id()).update();
                }
                insert("availability_intervals", next.id(), next.intervals());
                insert("blocked_intervals", next.id(), next.blockedIntervals());
                return next;
            });
        } catch (DuplicateKeyException concurrentFirstReplacement) {
            throw new StaleVersionException();
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private Optional<DentistAvailability> load(UUID dentistId, String lock) {
        return jdbc.sql("SELECT schedule_id, version, created_at, created_by, updated_at, updated_by "
                        + "FROM appointments.dentist_schedules WHERE dentist_id = ?" + lock)
                .param(dentistId)
                .query((row, n) -> DentistAvailability.reconstitute(row.getObject(1, UUID.class), dentistId,
                        intervals("availability_intervals", row.getObject(1, UUID.class)),
                        intervals("blocked_intervals", row.getObject(1, UUID.class)), row.getLong(2),
                        row.getObject(3, OffsetDateTime.class).toInstant(), row.getObject(4, UUID.class),
                        row.getObject(5, OffsetDateTime.class).toInstant(), row.getObject(6, UUID.class)))
                .optional();
    }

    private List<TimeInterval> intervals(String table, UUID scheduleId) {
        return jdbc.sql("SELECT start_at, end_at FROM appointments." + table
                        + " WHERE schedule_id = ? ORDER BY start_at")
                .param(scheduleId)
                .query((row, n) -> new TimeInterval(utc(row.getObject(1, OffsetDateTime.class)),
                        utc(row.getObject(2, OffsetDateTime.class))))
                .list();
    }

    private List<TimeInterval> activeAppointments(UUID dentistId) {
        return jdbc.sql("""
                SELECT start_at, end_at FROM appointments.appointments
                WHERE dentist_id = ? AND status IN ('PROGRAMADA','CONFIRMADA','EN_ATENCION')""")
                .param(dentistId)
                .query((row, n) -> new TimeInterval(utc(row.getObject(1, OffsetDateTime.class)),
                        utc(row.getObject(2, OffsetDateTime.class))))
                .list();
    }

    private void insert(String table, UUID scheduleId, List<TimeInterval> intervals) {
        for (TimeInterval interval : intervals) {
            jdbc.sql("INSERT INTO appointments." + table + " VALUES (?, ?, ?, ?)")
                    .params(ids.newId(), scheduleId, Timestamp.from(interval.start().toInstant()),
                            Timestamp.from(interval.end().toInstant()))
                    .update();
        }
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
