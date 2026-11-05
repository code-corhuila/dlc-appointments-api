package co.edu.corhuila.dlc.appointments.domain.model;

/** Scheduling failure categories, independent of transport error codes. */
public final class SchedulingException extends RuntimeException {
    public enum Reason {
        PATIENT_INELIGIBLE, DENTIST_INELIGIBLE, SLOT_UNAVAILABLE, DEPENDENCY_UNAVAILABLE,
        AUTHORIZATION_DENIED, IDEMPOTENCY_CONFLICT
    }

    private final Reason reason;

    public SchedulingException(Reason reason) {
        super(java.util.Objects.requireNonNull(reason).name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
