package co.edu.corhuila.dlc.appointments.domain.model;

/** Structural domain error; contains no HTTP or infrastructure details. */
public final class InvalidAppointmentException extends RuntimeException {
    private final String field;

    public InvalidAppointmentException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() { return field; }
}
