package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import java.util.Optional;
import java.util.UUID;

/** Authoritative current appointment state, never a projection. */
@FunctionalInterface
public interface AppointmentReader {
    Optional<Appointment> findById(UUID appointmentId);
}
