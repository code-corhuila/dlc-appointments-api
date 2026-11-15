package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.Transition;
import java.util.Optional;
import java.util.function.Function;

/**
 * Applies one lifecycle decision atomically: replays a known Idempotency-Key, locks the appointment
 * (AppointmentNotFoundException when absent), asks {@code decide} for the transition and, when there is one,
 * commits the new version, its audit row, reminder effects, outbox event and the key together.
 * An empty decision leaves the appointment unchanged and returns it as it is (Annex C repeated cancel).
 */
public interface AppointmentLifecycleStore {
    Appointment apply(LifecycleCommand command, SchedulingContext context,
                      Function<Appointment, Optional<Transition>> decide);
}
