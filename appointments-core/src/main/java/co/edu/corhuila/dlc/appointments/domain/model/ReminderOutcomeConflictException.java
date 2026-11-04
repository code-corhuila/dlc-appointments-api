package co.edu.corhuila.dlc.appointments.domain.model;

/** A worker outcome that cannot apply: obsolete delivery (STALE) or a different final outcome (CONFLICTING). */
public final class ReminderOutcomeConflictException extends RuntimeException {
    public enum Reason { STALE, CONFLICTING }

    private final Reason reason;

    public ReminderOutcomeConflictException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
