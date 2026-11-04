package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.model.AssignmentPage;
import co.edu.corhuila.dlc.appointments.application.model.AssignmentQuery;
import co.edu.corhuila.dlc.appointments.application.model.AssignmentResult;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.out.ClinicalAssignmentStore;
import co.edu.corhuila.dlc.appointments.application.port.out.IdGenerator;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentConflictException;
import co.edu.corhuila.dlc.appointments.domain.model.AssignmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignment;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HU-APT-004 clinical_assignments. Each write commits the row, its outbox event and the Idempotency-Key
 * (scope: actor + operation + key) together; uk_clinical_assignments_active_patient_dentist keeps one ACTIVE
 * assignment per pair and surfaces as AppointmentConflictException.
 */
public final class JdbcClinicalAssignmentStore implements ClinicalAssignmentStore {
    private static final String START = "START_CLINICAL_ASSIGNMENT";
    private static final String END = "END_CLINICAL_ASSIGNMENT";
    private static final String COLUMNS = "assignment_id, patient_id, dentist_id, status, reason, started_at, "
            + "started_by, ended_at, ended_by, end_reason, version";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final JdbcIdempotencyStore keys;
    private final JdbcOutboxWriter outbox;
    private final IdGenerator ids;

    public JdbcClinicalAssignmentStore(TransactionTemplate transactions, JdbcClient jdbc, JdbcIdempotencyStore keys,
            JdbcOutboxWriter outbox, IdGenerator ids) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public Optional<AssignmentResult> recover(SchedulingContext context, ClinicalAssignment proposed) {
        try {
            return replay(context, START, startHash(proposed)).map(a -> new AssignmentResult(a, false));
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public AssignmentResult start(SchedulingContext context, ClinicalAssignment proposed) {
        try {
            return transactions.execute(status -> recover(context, proposed).orElseGet(() -> {
                insert(proposed);
                event("ClinicalAssignmentStarted", proposed, proposed.startedAt(), proposed.startedBy(), context);
                keys.save(new IdempotencyRecord(context.actorUserId(), START, context.idempotencyKey(),
                        startHash(proposed), "ClinicalAssignment", proposed.id(), 201, proposed.startedAt()));
                return new AssignmentResult(proposed, true);
            }));
        } catch (DuplicateIdempotencyKeyException concurrentRetry) {
            return recover(context, proposed).orElseThrow(
                    () -> new SchedulingException(SchedulingException.Reason.IDEMPOTENCY_CONFLICT));
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public ClinicalAssignment end(UUID id, String requestHashInput, SchedulingContext context,
                                  UnaryOperator<ClinicalAssignment> decide) {
        String hash = sha256(requestHashInput);
        try {
            return transactions.execute(status -> replay(context, END, hash).orElseGet(() -> {
                ClinicalAssignment current = select(" WHERE assignment_id = ? FOR UPDATE", id)
                        .orElseThrow(ClinicalAssignmentNotFoundException::new);
                ClinicalAssignment ended = decide.apply(current);
                jdbc.sql("""
                        UPDATE appointments.clinical_assignments
                        SET status = ?, ended_at = ?, ended_by = ?, end_reason = ?, version = ?
                        WHERE assignment_id = ? AND version = ?""")
                        .params(ended.status().name(), Timestamp.from(ended.endedAt()), ended.endedBy(),
                                ended.endReason(), ended.version(), id, current.version())
                        .update();
                event("ClinicalAssignmentEnded", ended, ended.endedAt(), ended.endedBy(), context);
                keys.save(new IdempotencyRecord(context.actorUserId(), END, context.idempotencyKey(), hash,
                        "ClinicalAssignment", id, 200, ended.endedAt()));
                return ended;
            }));
        } catch (DuplicateIdempotencyKeyException concurrentRetry) {
            return replay(context, END, hash).orElseThrow(
                    () -> new SchedulingException(SchedulingException.Reason.IDEMPOTENCY_CONFLICT));
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public Optional<ClinicalAssignment> find(UUID id) {
        try {
            return select(" WHERE assignment_id = ?", id);
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Filters first, then counts and pages; most recent first with the id as a stable tie-break. */
    @Override
    public AssignmentPage search(AssignmentQuery query) {
        var params = new HashMap<String, Object>();
        var where = new StringBuilder(" WHERE true");
        query.patientId().ifPresent(p -> { where.append(" AND patient_id = :patient"); params.put("patient", p); });
        query.dentistId().ifPresent(d -> { where.append(" AND dentist_id = :dentist"); params.put("dentist", d); });
        try {
            long total = jdbc.sql("SELECT count(*) FROM appointments.clinical_assignments" + where).params(params)
                    .query(Long.class).single();
            params.put("limit", query.limit());
            params.put("offset", (long) (query.page() - 1) * query.limit());
            var items = jdbc.sql("SELECT " + COLUMNS + " FROM appointments.clinical_assignments" + where
                            + " ORDER BY started_at DESC, assignment_id DESC LIMIT :limit OFFSET :offset")
                    .params(params).query((row, n) -> map(row)).list();
            return new AssignmentPage(items, query.page(), query.limit(), total);
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private Optional<ClinicalAssignment> replay(SchedulingContext context, String operation, String hash) {
        return keys.find(context.actorUserId(), operation, context.idempotencyKey()).map(stored -> {
            if (!stored.requestHash().equals(hash)) {
                throw new SchedulingException(SchedulingException.Reason.IDEMPOTENCY_CONFLICT);
            }
            return select(" WHERE assignment_id = ?", stored.resourceId()).orElseThrow(
                    () -> new IllegalStateException("Idempotency key references a missing assignment."));
        });
    }

    private void insert(ClinicalAssignment a) {
        try {
            jdbc.sql("INSERT INTO appointments.clinical_assignments (" + COLUMNS
                            + ") VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?)")
                    .params(a.id(), a.patientId(), a.dentistId(), a.status().name(), a.reason(),
                            Timestamp.from(a.startedAt()), a.startedBy(), a.version())
                    .update();
        } catch (DuplicateKeyException activePair) {
            throw new AppointmentConflictException("The patient already has an active assignment with the Dentist.");
        }
    }

    private void event(String type, ClinicalAssignment a, Instant at, UUID by, SchedulingContext context) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("assignmentId", a.id());
        payload.put("patientId", a.patientId());
        payload.put("dentistId", a.dentistId());
        payload.put(type.endsWith("Started") ? "startedAt" : "endedAt", at.toString());
        payload.put(type.endsWith("Started") ? "startedBy" : "endedBy", by);
        payload.put("version", a.version());
        try {
            outbox.append(new OutboxEvent(ids.newId(), type, 1, "ClinicalAssignment", a.id(),
                    JSON.writeValueAsString(payload), context.correlationId(), context.correlationId(),
                    context.actorUserId(), at));
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private Optional<ClinicalAssignment> select(String condition, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM appointments.clinical_assignments" + condition)
                .param(id).query((row, n) -> map(row)).optional();
    }

    private static ClinicalAssignment map(ResultSet row) throws SQLException {
        return ClinicalAssignment.reconstitute(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                row.getObject(3, UUID.class), AssignmentStatus.valueOf(row.getString(4)), row.getString(5),
                instant(row, 6), row.getObject(7, UUID.class), instant(row, 8), row.getObject(9, UUID.class),
                row.getString(10), row.getLong(11));
    }

    private static Instant instant(ResultSet row, int column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String startHash(ClinicalAssignment a) {
        return sha256(a.patientId() + "\n" + a.dentistId() + "\n" + a.reason());
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
