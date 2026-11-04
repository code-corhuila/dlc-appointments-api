package co.edu.corhuila.dlc.appointments.application.model;

import java.util.List;
import java.util.UUID;

/** One page of a Dentist's current patients, read from a single snapshot identified by assignmentsVersion. */
public record AssignedPatientsPage(UUID dentistId, long assignmentsVersion, List<CareRelationship> items, int page,
        int limit, long total) {
    public AssignedPatientsPage {
        items = List.copyOf(items);
    }

    public long totalPages() {
        return (total + limit - 1) / limit;
    }
}
