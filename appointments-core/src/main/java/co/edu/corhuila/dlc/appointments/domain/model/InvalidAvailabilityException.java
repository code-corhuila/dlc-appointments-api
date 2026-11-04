package co.edu.corhuila.dlc.appointments.domain.model;

import java.util.List;

/** Availability input that breaks interval rules, with one error per offending position. */
public final class InvalidAvailabilityException extends RuntimeException {
    private final List<FieldError> errors;

    public InvalidAvailabilityException(List<FieldError> errors) {
        super("The availability is invalid.");
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
