package co.edu.corhuila.dlc.appointments.domain.model;

/** The expectedVersion sent by the client is not the current version (optimistic concurrency). */
public final class StaleVersionException extends RuntimeException {
    public StaleVersionException() {
        super("The resource has changed.");
    }
}
