package co.edu.corhuila.dlc.appointments.application.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Verified reader identity and current roles for scoped appointment reads. */
public record ReaderContext(UUID actorUserId, Set<String> roles) {
    public ReaderContext {
        Objects.requireNonNull(actorUserId, "actorUserId");
        roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
    }
}
