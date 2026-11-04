package co.edu.corhuila.dlc.appointments.adapter.out.authorization;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.out.SchedulingAuthorization;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.util.Set;

/**
 * Only Secretary/Assistant and Administrator manage appointments (APT-008/009); a Dentist only reads.
 * The roles come from the access token verified for this request, with the session revalidated by Auth.
 */
public final class RoleSchedulingAuthorization implements SchedulingAuthorization {
    private static final Set<String> SCHEDULERS = Set.of("ADMINISTRATOR", "SECRETARY_ASSISTANT");

    @Override
    public void requireSchedulingAllowed(SchedulingContext context) {
        if (context.roles().stream().noneMatch(SCHEDULERS::contains)) {
            throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
        }
    }
}
