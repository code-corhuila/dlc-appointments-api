package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.WaitingListEntry;
import co.edu.corhuila.dlc.appointments.application.port.out.WaitingListStore;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentConflictException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import co.edu.corhuila.dlc.appointments.domain.model.WaitingListEntryNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HU-APT-005 appointments.waiting_list_entries. Each write commits the entry and its Idempotency-Key
 * (actor + operation + key) together; uk_waiting_list_entries_waiting_patient_dentist keeps one WAITING entry per
 * patient and preferred dentist and surfaces as AppointmentConflictException.
 */
public final class JdbcWaitingListStore implements WaitingListStore {
    private static final String CREATE = "CREATE_WAITING_LIST_ENTRY";
    private static final String RESOLVE = "RESOLVE_WAITING_LIST_ENTRY";
    private static final String COLUMNS = "entry_id, patient_id, dentist_id, reason, preferred_from, preferred_to, "
            + "status, appointment_id, created_at, created_by, resolved_at, resolved_by, version";
    private final TransactionTemplate transactions;
    private final JdbcClient jdbc;
    private final JdbcIdempotencyStore keys;

    public JdbcWaitingListStore(TransactionTemplate transactions, JdbcClient jdbc, JdbcIdempotencyStore keys) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override
    public Optional<Result> recover(SchedulingContext context, String requestHash) {
        return unavailableOnFailure(() -> replay(context, CREATE, sha256(requestHash)).map(e -> new Result(e, false)));
    }

    @Override
    public Result create(SchedulingContext context, WaitingListEntry entry, String requestHash) {
        String hash = sha256(requestHash);
        try {
            return transactions.execute(status -> replay(context, CREATE, hash).map(e -> new Result(e, false))
                    .orElseGet(() -> {
                        insert(entry);
                        keys.save(new IdempotencyRecord(context.actorUserId(), CREATE, context.idempotencyKey(),
                                hash, "WaitingListEntry", entry.id(), 201, entry.createdAt()));
                        return new Result(entry, true);
                    }));
        } catch (DuplicateIdempotencyKeyException concurrentRetry) {
            return replay(context, CREATE, hash).map(e -> new Result(e, false)).orElseThrow(
                    () -> new SchedulingException(SchedulingException.Reason.IDEMPOTENCY_CONFLICT));
        } catch (DuplicateKeyException waiting) {
            throw new AppointmentConflictException("The patient already waits for this dentist.");
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public WaitingListEntry resolve(UUID id, String requestHash, SchedulingContext context,
            UnaryOperator<WaitingListEntry> decide) {
        String hash = sha256(requestHash);
        try {
            return transactions.execute(status -> replay(context, RESOLVE, hash).orElseGet(() -> {
                var current = select(" WHERE entry_id = ? FOR UPDATE", id)
                        .orElseThrow(WaitingListEntryNotFoundException::new);
                var next = decide.apply(current);
                jdbc.sql("""
                        UPDATE appointments.waiting_list_entries
                        SET status = ?, appointment_id = ?, resolved_at = ?, resolved_by = ?, version = ?
                        WHERE entry_id = ? AND version = ?""")
                        .params(next.status().name(), next.appointmentId(), Timestamp.from(next.resolvedAt()),
                                next.resolvedBy(), next.version(), id, current.version())
                        .update();
                keys.save(new IdempotencyRecord(context.actorUserId(), RESOLVE, context.idempotencyKey(), hash,
                        "WaitingListEntry", id, 200, next.resolvedAt()));
                return next;
            }));
        } catch (DuplicateIdempotencyKeyException concurrentRetry) {
            return replay(context, RESOLVE, hash).orElseThrow(
                    () -> new SchedulingException(SchedulingException.Reason.IDEMPOTENCY_CONFLICT));
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    @Override
    public Page list(WaitingListEntry.Status status, int page, int limit) {
        return unavailableOnFailure(() -> {
            long total = jdbc.sql("SELECT count(*) FROM appointments.waiting_list_entries WHERE status = ?")
                    .param(status.name()).query(Long.class).single();
            var data = jdbc.sql("SELECT " + COLUMNS + " FROM appointments.waiting_list_entries WHERE status = ? "
                    + "ORDER BY created_at ASC, entry_id ASC LIMIT ? OFFSET ?")
                    .params(status.name(), limit, (long) (page - 1) * limit)
                    .query((row, n) -> entry(row)).list();
            return new Page(data, page, limit, total);
        });
    }

    @Override
    public Optional<UUID> appointmentPatient(UUID appointmentId) {
        return unavailableOnFailure(() -> jdbc.sql("SELECT patient_id FROM appointments.appointments "
                        + "WHERE appointment_id = ? AND status NOT IN ('CANCELADA','NO_ASISTIO')")
                .param(appointmentId).query(UUID.class).optional());
    }

    private void insert(WaitingListEntry e) {
        jdbc.sql("INSERT INTO appointments.waiting_list_entries (" + COLUMNS
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(e.id(), e.patientId(), e.dentistId(), e.reason(), date(e.preferredFrom()),
                        date(e.preferredTo()), e.status().name(), e.appointmentId(), Timestamp.from(e.createdAt()),
                        e.createdBy(), null, null, e.version())
                .update();
    }

    private Optional<WaitingListEntry> replay(SchedulingContext context, String operation, String hash) {
        return keys.find(context.actorUserId(), operation, context.idempotencyKey()).map(record -> {
            if (!record.requestHash().equals(hash)) {
                throw new SchedulingException(SchedulingException.Reason.IDEMPOTENCY_CONFLICT);
            }
            return select(" WHERE entry_id = ?", record.resourceId()).orElseThrow();
        });
    }

    private Optional<WaitingListEntry> select(String where, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM appointments.waiting_list_entries" + where).param(id)
                .query((row, n) -> entry(row)).optional();
    }

    private static WaitingListEntry entry(ResultSet row) throws SQLException {
        Date from = row.getDate("preferred_from");
        Date to = row.getDate("preferred_to");
        Timestamp resolved = row.getTimestamp("resolved_at");
        return new WaitingListEntry(row.getObject("entry_id", UUID.class), row.getObject("patient_id", UUID.class),
                row.getObject("dentist_id", UUID.class), row.getString("reason"),
                from == null ? null : from.toLocalDate(), to == null ? null : to.toLocalDate(),
                WaitingListEntry.Status.valueOf(row.getString("status")), row.getObject("appointment_id", UUID.class),
                row.getTimestamp("created_at").toInstant(), row.getObject("created_by", UUID.class),
                resolved == null ? null : resolved.toInstant(), row.getObject("resolved_by", UUID.class),
                row.getLong("version"));
    }

    private static Date date(java.time.LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    private static <T> T unavailableOnFailure(java.util.function.Supplier<T> read) {
        try {
            return read.get();
        } catch (DataAccessException unavailable) {
            throw new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
