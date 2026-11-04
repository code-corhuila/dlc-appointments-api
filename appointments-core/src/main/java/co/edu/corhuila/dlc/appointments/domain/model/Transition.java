package co.edu.corhuila.dlc.appointments.domain.model;

/** The next appointment version together with the audit facts that produced it. */
public record Transition(Appointment appointment, StatusChange change) {
}
