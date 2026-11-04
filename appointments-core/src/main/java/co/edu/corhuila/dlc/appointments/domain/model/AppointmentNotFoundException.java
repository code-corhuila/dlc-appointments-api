package co.edu.corhuila.dlc.appointments.domain.model;

/** The appointment does not exist or is outside the reader's scope; both answer the same way. */
public final class AppointmentNotFoundException extends RuntimeException {
    public AppointmentNotFoundException() {
        super("The appointment does not exist.");
    }
}
