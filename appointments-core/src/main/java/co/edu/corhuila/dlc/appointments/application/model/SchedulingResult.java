package co.edu.corhuila.dlc.appointments.application.model;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import java.util.Objects;

/** Original creation snapshot and version, not the appointment's current lifecycle state. */
public record SchedulingResult(Appointment appointment, long version) {
    public SchedulingResult {
        Objects.requireNonNull(appointment, "appointment");
        if (version < 1) throw new IllegalArgumentException("Version must be positive.");
    }
}
