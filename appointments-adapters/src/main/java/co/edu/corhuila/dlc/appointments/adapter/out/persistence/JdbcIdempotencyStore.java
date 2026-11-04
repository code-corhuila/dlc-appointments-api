package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** DB-08 idempotency records; callers run it inside the transaction of the change the key protects. */
public final class JdbcIdempotencyStore {
    private final JdbcClient jdbc;

    public JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Optional<IdempotencyRecord> find(UUID actorUserId, String operation, String idempotencyKey) {
        return jdbc.sql("""
                SELECT actor_user_id, operation, idempotency_key, request_hash, resource_type, resource_id,
                       response_status, created_at
                FROM appointments.idempotency_keys
                WHERE actor_user_id = ? AND operation = ? AND idempotency_key = ?""")
                .params(actorUserId, operation, idempotencyKey)
                .query((row, n) -> new IdempotencyRecord(row.getObject(1, UUID.class), row.getString(2),
                        row.getString(3), row.getString(4), row.getString(5), row.getObject(6, UUID.class),
                        row.getInt(7), row.getObject(8, OffsetDateTime.class).toInstant()))
                .optional();
    }

    public void save(IdempotencyRecord record) {
        try {
            jdbc.sql("""
                    INSERT INTO appointments.idempotency_keys (actor_user_id, operation, idempotency_key,
                        request_hash, resource_type, resource_id, response_status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(record.actorUserId(), record.operation(), record.idempotencyKey(), record.requestHash(),
                            record.resourceType(), record.resourceId(), record.responseStatus(),
                            Timestamp.from(record.createdAt()))
                    .update();
        } catch (DuplicateKeyException duplicate) {
            throw new DuplicateIdempotencyKeyException();
        }
    }
}
