package co.edu.corhuila.dlc.appointments.application.port.out;

import java.util.Optional;
import java.util.UUID;

/** D8: the Dentist's ClinicalCareCompleted declaration recorded for an appointment, if any. */
public interface CareCompletions {
    Optional<Declaration> find(UUID appointmentId);

    record Declaration(UUID patientId, UUID dentistId) { }
}
