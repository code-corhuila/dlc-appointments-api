package co.edu.corhuila.dlc.appointments.domain.model;

/** No active Dentist, no stored availability, or a Dentist reading another dentist; all answer the same. */
public final class DentistNotFoundException extends RuntimeException {
    public DentistNotFoundException() {
        super("The dentist availability does not exist.");
    }
}
