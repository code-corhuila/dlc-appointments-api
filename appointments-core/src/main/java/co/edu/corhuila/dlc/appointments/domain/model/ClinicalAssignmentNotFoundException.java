package co.edu.corhuila.dlc.appointments.domain.model;

/** Absent assignment; answered as 404. */
public final class ClinicalAssignmentNotFoundException extends RuntimeException {
    public ClinicalAssignmentNotFoundException() {
        super("The assignment does not exist.");
    }
}
