package co.edu.corhuila.dlc.appointments.application.model;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import java.util.Objects;

/** Original creation snapshot and version; created is false when an idempotent retry replays it. */
public record SchedulingResult(Appointment appointment, long version, boolean created) {
    public SchedulingResult {
        Objects.requireNonNull(appointment, "appointment");
        if (version < 1) throw new IllegalArgumentException("Version must be positive.");
    }
}
