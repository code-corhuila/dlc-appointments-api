package co.edu.corhuila.dlc.appointments.application.model;

import java.util.Objects;
import java.util.UUID;

/** Previously verified identity; identifiers alone never authorize an attempt.
 * The Idempotency-Key has 8 to 128 characters (Norma 5.3.8); a UUID is one valid form.
 */
public record SchedulingContext(UUID actorUserId, UUID sessionId, String idempotencyKey) {
    public SchedulingContext {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must have 8 to 128 characters.");
        }
    }
}
