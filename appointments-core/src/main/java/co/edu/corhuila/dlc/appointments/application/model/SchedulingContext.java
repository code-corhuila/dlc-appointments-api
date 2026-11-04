package co.edu.corhuila.dlc.appointments.application.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Previously verified identity, its current roles and the request correlation; identifiers alone never
 * authorize an attempt. The Idempotency-Key has 8 to 128 characters (Norma 5.3.8); a UUID is one valid form.
 */
public record SchedulingContext(UUID actorUserId, UUID sessionId, String idempotencyKey, Set<String> roles,
        UUID correlationId) {
    public SchedulingContext {
        Objects.requireNonNull(actorUserId, "actorUserId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
        Objects.requireNonNull(correlationId, "correlationId");
        if (idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must have 8 to 128 characters.");
        }
    }
}
