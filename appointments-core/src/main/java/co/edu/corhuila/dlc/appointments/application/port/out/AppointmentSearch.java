package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.application.model.AppointmentPage;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentStatus;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** Authorized calendar search, most recent first (createdAt, then id, descending). */
@FunctionalInterface
public interface AppointmentSearch {
    AppointmentPage search(Criteria criteria);

    record Criteria(UUID patientId, UUID dentistId, OffsetDateTime from, OffsetDateTime to,
            Set<AppointmentStatus> statuses, int page, int limit) {
        public Criteria {
            statuses = Set.copyOf(statuses);
        }
    }
}
