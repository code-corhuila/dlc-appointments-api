package co.edu.corhuila.dlc.appointments.application.model;

import java.util.Optional;
import java.util.UUID;

/** Assignment list filters with Norma 5.3.6 paging (page >= 1, limit 1 to 100). */
public record AssignmentQuery(int page, int limit, Optional<UUID> patientId, Optional<UUID> dentistId) {
}
