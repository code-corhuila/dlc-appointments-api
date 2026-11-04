package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.domain.model.DentistAvailability;
import co.edu.corhuila.dlc.appointments.domain.model.TimeInterval;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Owned availability storage. replace runs the change atomically against the locked current state and the
 * dentist's active appointments, so a concurrent booking cannot slip between the check and the commit.
 */
public interface DentistAvailabilityStore {
    Optional<DentistAvailability> find(UUID dentistId);

    DentistAvailability replace(UUID dentistId, Change change);

    @FunctionalInterface
    interface Change {
        DentistAvailability apply(Optional<DentistAvailability> current, List<TimeInterval> activeAppointments);
    }
}
