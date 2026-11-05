package co.edu.corhuila.dlc.appointments.domain.model;

/** Audited lifecycle operations after SCHEDULE, as named in appointment_status_changes. */
public enum LifecycleOperation {
    CONFIRM, CANCEL, RESCHEDULE, START_ATTENTION, NO_SHOW, COMPLETE
}
