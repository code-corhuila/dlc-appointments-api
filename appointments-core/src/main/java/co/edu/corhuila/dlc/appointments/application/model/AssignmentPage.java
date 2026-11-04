package co.edu.corhuila.dlc.appointments.application.model;

import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignment;
import java.util.List;

/** One bounded page of assignments, most recent first, with the total of the filtered set. */
public record AssignmentPage(List<ClinicalAssignment> items, int page, int limit, long total) {
    public AssignmentPage {
        items = List.copyOf(items);
    }
}
