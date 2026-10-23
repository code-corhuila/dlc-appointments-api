package co.edu.corhuila.dlc.appointments.application.port.out;

import java.util.UUID;

/** Checks current verified actor/session eligibility and scheduling permission on every attempt.
 * Throws SchedulingException(AUTHORIZATION_DENIED) on denial, DEPENDENCY_UNAVAILABLE on failure.
 * Possession of identifiers alone is insufficient; adapters must verify the trusted identity.
 */
public interface SchedulingAuthorization {
    void requireSchedulingAllowed(UUID actorUserId, UUID sessionId);
}
