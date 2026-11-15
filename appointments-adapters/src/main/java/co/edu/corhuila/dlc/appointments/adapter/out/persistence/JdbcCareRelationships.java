package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.model.AssignedPatientsPage;
import co.edu.corhuila.dlc.appointments.application.model.CareRelationship;
import co.edu.corhuila.dlc.appointments.application.port.out.CareRelationships;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Access sources from appointments (PROGRAMADA/CONFIRMADA/EN_ATENCION) and ACTIVE clinical_assignments.
 * Versions are the sum of the versions of every appointment and assignment involved (at least 1): any start,
 * transition or ending strictly increases them. A Dentist page is read in one REPEATABLE READ snapshot.
 */
public final class JdbcCareRelationships implements CareRelationships {
    private static final String LIVE = "('PROGRAMADA','CONFIRMADA','EN_ATENCION')";
    private static final String PAIR_VERSION = """
            greatest(1, coalesce((SELECT sum(version) FROM appointments.appointments x
                                  WHERE x.patient_id = %1$s AND x.dentist_id = %2$s), 0)
                      + coalesce((SELECT sum(version) FROM appointments.clinical_assignments y
                                  WHERE y.patient_id = %1$s AND y.dentist_id = %2$s), 0))""";
    private final TransactionTemplate snapshots;
    private final JdbcClient jdbc;

    public JdbcCareRelationships(PlatformTransactionManager transactions, JdbcClient jdbc) {
        this.snapshots = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
        this.snapshots.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshots.setReadOnly(true);
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public CareRelationship between(UUID patientId, UUID dentistId) {
        try {
            return snapshots.execute(status -> jdbc.sql("SELECT " + PAIR_VERSION.formatted(":p", ":d") + """
                            , EXISTS (SELECT 1 FROM appointments.appointments
                                      WHERE patient_id = :p AND dentist_id = :d AND status IN """ + LIVE + """
                            ), EXISTS (SELECT 1 FROM appointments.clinical_assignments
                                      WHERE patient_id = :p AND dentist_id = :d AND status = 'ACTIVE')""")
                    .param("p", patientId).param("d", dentistId)
                    .query((row, n) -> new CareRelationship(patientId, dentistId, row.getLong(1),
                            types(row.getBoolean(2), row.getBoolean(3))))
                    .single());
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public AssignedPatientsPage assignedTo(UUID dentistId, int page, int limit) {
        String sources = """
                WITH src AS (
                    SELECT patient_id, 'APPOINTMENT' AS t FROM appointments.appointments
                    WHERE dentist_id = :d AND status IN """ + LIVE + """
                    UNION SELECT patient_id, 'ONGOING_ASSIGNMENT' FROM appointments.clinical_assignments
                    WHERE dentist_id = :d AND status = 'ACTIVE'),
                patients AS (SELECT patient_id, array_agg(DISTINCT t ORDER BY t) AS types FROM src GROUP BY patient_id)
                """;
        try {
            return snapshots.execute(status -> {
                long version = jdbc.sql("""
                        SELECT greatest(1, coalesce((SELECT sum(version) FROM appointments.appointments
                                                     WHERE dentist_id = :d), 0)
                                         + coalesce((SELECT sum(version) FROM appointments.clinical_assignments
                                                     WHERE dentist_id = :d), 0))""")
                        .param("d", dentistId).query(Long.class).single();
                long total = jdbc.sql(sources + "SELECT count(*) FROM patients").param("d", dentistId)
                        .query(Long.class).single();
                List<CareRelationship> items = jdbc.sql(sources + "SELECT patient_id, types, "
                                + PAIR_VERSION.formatted("patients.patient_id", ":d")
                                + " FROM patients ORDER BY patient_id LIMIT :limit OFFSET :offset")
                        .param("d", dentistId).param("limit", limit).param("offset", (long) (page - 1) * limit)
                        .query((row, n) -> new CareRelationship(row.getObject(1, UUID.class), dentistId,
                                row.getLong(3), strings(row.getArray(2))))
                        .list();
                return new AssignedPatientsPage(dentistId, version, items, page, limit, total);
            });
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static List<String> types(boolean appointment, boolean ongoing) {
        if (appointment && ongoing) return List.of("APPOINTMENT", "ONGOING_ASSIGNMENT");
        if (appointment) return List.of("APPOINTMENT");
        return ongoing ? List.of("ONGOING_ASSIGNMENT") : List.of();
    }

    private static List<String> strings(Array array) throws java.sql.SQLException {
        return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }
}
