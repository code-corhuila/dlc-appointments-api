package co.edu.corhuila.dlc.appointments.domain.model;

/** No guard for that id or operationId was created by the calling service (404, indistinguishable). */
public final class PatientGuardNotFoundException extends RuntimeException {
    public PatientGuardNotFoundException() {
        super("The guard does not exist.");
    }
}
