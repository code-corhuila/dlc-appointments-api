package co.edu.corhuila.dlc.appointments.domain.model;

/** A business-state conflict that is not a transition error: 24-hour window, timing or care readiness. */
public final class AppointmentConflictException extends RuntimeException {
    public AppointmentConflictException(String message) {
        super(message);
    }
}
