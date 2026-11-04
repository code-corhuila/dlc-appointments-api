package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Strict request bodies of the HU-APT-004 writes (additionalProperties: false). */
public final class AssignmentRequests {
    private AssignmentRequests() { }

    public record Start(UUID patientId, UUID dentistId, String reason) {
        public List<ApiError.Detail> missing() {
            var details = new ArrayList<ApiError.Detail>();
            if (patientId == null) details.add(new ApiError.Detail("patientId", "Required UUID."));
            if (dentistId == null) details.add(new ApiError.Detail("dentistId", "Required UUID."));
            if (reason == null || reason.isBlank()) {
                details.add(new ApiError.Detail("reason", "Required, 1 to 1000 characters."));
            }
            return details;
        }
    }

    public record End(String reason, Long expectedVersion) {
        public List<ApiError.Detail> missing() {
            var details = new ArrayList<ApiError.Detail>();
            if (reason == null || reason.isBlank()) {
                details.add(new ApiError.Detail("reason", "Required, 1 to 1000 characters."));
            }
            if (expectedVersion == null || expectedVersion < 1) {
                details.add(new ApiError.Detail("expectedVersion", "Required, an integer of at least 1."));
            }
            return details;
        }
    }
}
