package co.edu.corhuila.dlc.appointments.domain.model;

/** One invalid input position or field, reported to the client as a validation detail. */
public record FieldError(String field, String message) { }
