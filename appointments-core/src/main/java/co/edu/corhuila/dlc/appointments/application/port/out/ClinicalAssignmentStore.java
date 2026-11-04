package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.application.model.AssignmentPage;
import co.edu.corhuila.dlc.appointments.application.model.AssignmentQuery;
import co.edu.corhuila.dlc.appointments.application.model.AssignmentResult;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignment;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * clinical_assignments with its outbox events and Idempotency-Keys. {@code start} commits the row,
 * ClinicalAssignmentStarted and the key together; a second ACTIVE pair raises AppointmentConflictException.
 * {@code end} replays a known key, otherwise locks the row (ClinicalAssignmentNotFoundException when absent),
 * applies {@code decide} and commits the ended row, ClinicalAssignmentEnded and the key together.
 */
public interface ClinicalAssignmentStore {
    Optional<AssignmentResult> recover(SchedulingContext context, ClinicalAssignment proposed);

    AssignmentResult start(SchedulingContext context, ClinicalAssignment proposed);

    ClinicalAssignment end(UUID id, String requestHashInput, SchedulingContext context,
                           UnaryOperator<ClinicalAssignment> decide);

    Optional<ClinicalAssignment> find(UUID id);

    AssignmentPage search(AssignmentQuery query);
}
