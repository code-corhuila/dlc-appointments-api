package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignment;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** Contract ClinicalAssignment: metadata only, RFC 3339 UTC; endedAt/endedBy appear only once ENDED. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClinicalAssignmentResponse(UUID id, UUID patientId, UUID dentistId, String status, String reason,
        String startedAt, UUID startedBy, String endedAt, UUID endedBy, long version) {
    public static ClinicalAssignmentResponse from(ClinicalAssignment a) {
        return new ClinicalAssignmentResponse(a.id(), a.patientId(), a.dentistId(), a.status().name(), a.reason(),
                a.startedAt().toString(), a.startedBy(), a.endedAt() == null ? null : a.endedAt().toString(),
                a.endedBy(), a.version());
    }
}
