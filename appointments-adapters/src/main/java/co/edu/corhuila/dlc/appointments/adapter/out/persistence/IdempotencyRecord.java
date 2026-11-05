package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

/** One DB-08 idempotency row: what a key already produced, for replay or conflict detection. */
public record IdempotencyRecord(UUID actorUserId, String operation, String idempotencyKey, String requestHash,
        String resourceType, UUID resourceId, int responseStatus, Instant createdAt) { }
