package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.port.out.PatientGuardStore;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentConflictException;
import co.edu.corhuila.dlc.appointments.domain.model.GuardKind;
import co.edu.corhuila.dlc.appointments.domain.model.GuardState;
import co.edu.corhuila.dlc.appointments.domain.model.PatientGuard;
import co.edu.corhuila.dlc.appointments.domain.model.PatientGuardNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Guards lock the patient's gate row FOR UPDATE, so they serialize with every gated operation (which holds it
 * FOR SHARE until commit). A deactivation guard needs an OPEN gate, no PROGRAMADA/CONFIRMADA/EN_ATENCION
 * appointment and no OPEN activity guard, and sets the gate BLOCKED; FINALIZED makes it INACTIVE and ABORTED
 * reopens it. An activity guard needs an OPEN gate and leaves it unchanged.
 */
public final class JdbcPatientGuardStore implements PatientGuardStore {
    private static final String COLUMNS = "guard_id, patient_id, operation_id, kind, state";
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;

    public JdbcPatientGuardStore(TransactionTemplate transactions, JdbcClient jdbc) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public PatientGuard acquire(UUID patientId, UUID operationId, String caller, GuardKind kind, UUID newGuardId,
                                String tokenHash, Instant now) {
        try {
            return acquireOnce(patientId, operationId, caller, kind, newGuardId, tokenHash, now);
        } catch (DuplicateKeyException concurrentRepeat) {
            // The same operation was acquired concurrently: answer it as a repeat with this token.
            return acquireOnce(patientId, operationId, caller, kind, newGuardId, tokenHash, now);
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private PatientGuard acquireOnce(UUID patientId, UUID operationId, String caller, GuardKind kind,
                                     UUID newGuardId, String tokenHash, Instant now) {
        return transactions.execute(status -> {
            Timestamp at = Timestamp.from(now);
            Optional<PatientGuard> existing = select(" WHERE patient_id = ? AND operation_id = ? AND caller_service = ? "
                    + "FOR UPDATE", patientId, operationId, caller);
            if (existing.isPresent()) {
                if (existing.get().kind() != kind) throw conflict("The operation already holds another guard kind.");
                jdbc.sql("UPDATE appointments.patient_guards SET token_hash = ? WHERE guard_id = ?")
                        .params(tokenHash, existing.get().id()).update();
                return existing.get();
            }
            String gate = lockGate(patientId, at);
            if (!"OPEN".equals(gate)) throw conflict("The patient gate is not open.");
            if (kind == GuardKind.DEACTIVATION) {
                boolean busy = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM appointments.appointments WHERE patient_id = :p
                                           AND status IN ('PROGRAMADA','CONFIRMADA','EN_ATENCION'))
                            OR EXISTS (SELECT 1 FROM appointments.patient_guards WHERE patient_id = :p
                                           AND kind = 'ACTIVITY' AND state = 'OPEN')""")
                        .param("p", patientId).query(Boolean.class).single();
                if (busy) throw conflict("The patient has live appointments or clinical activity in progress.");
                setGate(patientId, "BLOCKED", at);
            }
            jdbc.sql("INSERT INTO appointments.patient_guards (" + COLUMNS + ", caller_service, token_hash, created_at, "
                            + "resolved_at) VALUES (?, ?, ?, ?, 'OPEN', ?, ?, ?, NULL)")
                    .params(newGuardId, patientId, operationId, kind.name(), caller, tokenHash, at).update();
            return new PatientGuard(newGuardId, patientId, operationId, kind, GuardState.OPEN);
        });
    }

    @Override
    public Optional<PatientGuard> byOperation(UUID operationId, String caller) {
        return read(() -> select(" WHERE operation_id = ? AND caller_service = ? ORDER BY created_at LIMIT 1",
                operationId, caller));
    }

    @Override
    public Optional<PatientGuard> byId(UUID guardId, String caller) {
        return read(() -> select(" WHERE guard_id = ? AND caller_service = ?", guardId, caller));
    }

    @Override
    public PatientGuard resolve(UUID guardId, String caller, String tokenHash, GuardState outcome, Instant now) {
        try {
            return transactions.execute(status -> {
                PatientGuard guard = select(" WHERE guard_id = ? AND caller_service = ? FOR UPDATE", guardId, caller)
                        .orElseThrow(PatientGuardNotFoundException::new);
                String stored = jdbc.sql("SELECT token_hash FROM appointments.patient_guards WHERE guard_id = ?")
                        .param(guardId).query(String.class).single();
                if (!MessageDigest.isEqual(stored.getBytes(StandardCharsets.US_ASCII),
                        tokenHash.getBytes(StandardCharsets.US_ASCII))) {
                    throw conflict("The guard token is not current.");
                }
                if (guard.state() == outcome) return guard;
                if (guard.state() != GuardState.OPEN) throw conflict("The guard is already resolved.");
                Timestamp at = Timestamp.from(now);
                if (guard.kind() == GuardKind.DEACTIVATION) {
                    lockGate(guard.patientId(), at);
                    setGate(guard.patientId(), outcome == GuardState.FINALIZED ? "INACTIVE" : "OPEN", at);
                }
                jdbc.sql("UPDATE appointments.patient_guards SET state = ?, resolved_at = ? WHERE guard_id = ?")
                        .params(outcome.name(), at, guardId).update();
                return new PatientGuard(guard.id(), guard.patientId(), guard.operationId(), guard.kind(), outcome);
            });
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private String lockGate(UUID patientId, Timestamp at) {
        jdbc.sql("""
                INSERT INTO appointments.patient_gates (patient_id, status, version, updated_at)
                VALUES (?, 'OPEN', 1, ?) ON CONFLICT (patient_id) DO NOTHING""").params(patientId, at).update();
        return jdbc.sql("SELECT status FROM appointments.patient_gates WHERE patient_id = ? FOR UPDATE")
                .param(patientId).query(String.class).single();
    }

    private void setGate(UUID patientId, String status, Timestamp at) {
        jdbc.sql("UPDATE appointments.patient_gates SET status = ?, version = version + 1, updated_at = ? "
                + "WHERE patient_id = ?").params(status, at, patientId).update();
    }

    private Optional<PatientGuard> select(String condition, Object... params) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM appointments.patient_guards" + condition).params(params)
                .query((row, n) -> map(row)).optional();
    }

    private Optional<PatientGuard> read(Supplier<Optional<PatientGuard>> query) {
        try {
            return query.get();
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static PatientGuard map(ResultSet row) throws SQLException {
        return new PatientGuard(row.getObject(1, UUID.class), row.getObject(2, UUID.class), row.getObject(3, UUID.class),
                GuardKind.valueOf(row.getString(4)), GuardState.valueOf(row.getString(5)));
    }

    private static AppointmentConflictException conflict(String message) {
        return new AppointmentConflictException(message);
    }
}
