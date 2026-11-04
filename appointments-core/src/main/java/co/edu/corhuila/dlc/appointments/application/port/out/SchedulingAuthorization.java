package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;

/** Checks the verified actor's current scheduling permission on every attempt (APT-008/009).
 * Throws SchedulingException(AUTHORIZATION_DENIED) on denial, DEPENDENCY_UNAVAILABLE on failure.
 * Possession of identifiers alone is insufficient; adapters must rely on the verified identity.
 */
public interface SchedulingAuthorization {
    void requireSchedulingAllowed(SchedulingContext context);
}
