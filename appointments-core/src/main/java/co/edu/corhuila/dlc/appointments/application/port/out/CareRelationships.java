package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.application.model.AssignedPatientsPage;
import co.edu.corhuila.dlc.appointments.application.model.CareRelationship;
import java.util.UUID;

/** Authoritative access sources from the owner's rows, never from projections; failures fail closed (503). */
public interface CareRelationships {
    CareRelationship between(UUID patientId, UUID dentistId);

    AssignedPatientsPage assignedTo(UUID dentistId, int page, int limit);
}
