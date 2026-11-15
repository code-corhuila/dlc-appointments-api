package co.edu.corhuila.dlc.appointments.domain.model;

/** The state machine (APT-007) forbids the requested operation from the current status. */
public final class InvalidStatusTransitionException extends RuntimeException {
    public InvalidStatusTransitionException() {
        super("The current state does not allow this transition.");
    }
}
