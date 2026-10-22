package co.edu.corhuila.dlc.appointments.application.port.out;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Checks schedule availability, blocked intervals and active appointments (APT-002).
 * A positive answer is not a reservation or a concurrency guarantee.
 * Unknown/failed checks throw SchedulingException(DEPENDENCY_UNAVAILABLE).
 */
public interface AppointmentAvailability {
    boolean isAvailable(UUID dentistId, OffsetDateTime startAt, OffsetDateTime endAt);
}
