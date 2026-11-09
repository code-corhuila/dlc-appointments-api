package co.edu.corhuila.dlc.appointments.domain.model;

/** HU-APT-005: the waiting-list entry does not exist. */
public final class WaitingListEntryNotFoundException extends RuntimeException {
    public WaitingListEntryNotFoundException() {
        super("Waiting-list entry not found.", null, false, false);
    }
}
