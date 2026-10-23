package co.edu.corhuila.dlc.appointments.application.model;

import java.util.Objects;
import java.util.UUID;

/** Previously verified identity; identifiers alone never authorize an attempt. */
public record SchedulingContext(UUID actorUserId, UUID sessionId, UUID idempotencyKey) {
    public SchedulingContext {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    }
}
