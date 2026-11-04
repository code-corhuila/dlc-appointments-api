package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

/** A concurrent request committed the same actor, operation and key first; the caller re-reads it. */
public final class DuplicateIdempotencyKeyException extends RuntimeException {
    public DuplicateIdempotencyKeyException() {
        super("The idempotency key was already recorded.");
    }
}
