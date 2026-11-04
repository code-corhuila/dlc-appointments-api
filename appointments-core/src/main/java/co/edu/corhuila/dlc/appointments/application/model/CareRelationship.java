package co.edu.corhuila.dlc.appointments.application.model;

import java.util.List;
import java.util.UUID;

/**
 * Current access sources of one patient/Dentist pair (APPOINTMENT, ONGOING_ASSIGNMENT). The version grows with
 * every change to any of the pair's appointments or assignments, so a stale answer cannot pass as current.
 */
public record CareRelationship(UUID patientId, UUID dentistId, long version, List<String> sourceTypes) {
    public CareRelationship {
        sourceTypes = List.copyOf(sourceTypes);
    }

    public boolean assigned() {
        return !sourceTypes.isEmpty();
    }
}
