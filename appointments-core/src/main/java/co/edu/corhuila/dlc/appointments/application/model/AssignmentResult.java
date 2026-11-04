package co.edu.corhuila.dlc.appointments.application.model;

import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignment;

/** The assignment and whether this request created it (201) or replayed its key (200). */
public record AssignmentResult(ClinicalAssignment assignment, boolean created) {
}
