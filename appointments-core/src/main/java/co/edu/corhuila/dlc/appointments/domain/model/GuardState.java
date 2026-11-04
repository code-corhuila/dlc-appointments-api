package co.edu.corhuila.dlc.appointments.domain.model;

/** ADR-017 vocabulary: OPEN until resolved FINALIZED or ABORTED; never by time. */
public enum GuardState {
    OPEN, FINALIZED, ABORTED
}
