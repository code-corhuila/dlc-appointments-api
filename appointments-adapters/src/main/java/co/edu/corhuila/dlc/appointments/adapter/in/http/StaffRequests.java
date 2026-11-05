package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.AuthenticatedStaff;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Request facts shared by the write operations: the verified staff member, the key and the correlation. */
final class StaffRequests {
    private StaffRequests() { }

    static AuthenticatedStaff staff(HttpServletRequest request) {
        return (AuthenticatedStaff) request.getAttribute(AuthenticatedStaff.ATTRIBUTE);
    }

    static SchedulingContext context(HttpServletRequest request, String idempotencyKey) {
        var staff = staff(request);
        return new SchedulingContext(staff.userId(), staff.sessionId(), idempotencyKey, staff.roles(),
                correlationUuid(request));
    }

    /** Adds the Norma 5.3.8 header detail when the key is missing or outside 8 to 128 characters. */
    static void checkIdempotencyKey(String idempotencyKey, List<ApiError.Detail> details) {
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            details.add(new ApiError.Detail("Idempotency-Key", "Header required, 8 to 128 characters."));
        }
    }

    /** Event and audit metadata need a UUID; a non-UUID correlation ID maps to a stable name-based UUID. */
    private static UUID correlationUuid(HttpServletRequest request) {
        String correlation = String.valueOf(request.getAttribute(HttpExceptionHandler.TRUSTED_TRACE_ID_ATTRIBUTE));
        try {
            return UUID.fromString(correlation);
        } catch (IllegalArgumentException notUuid) {
            return UUID.nameUUIDFromBytes(correlation.getBytes(StandardCharsets.UTF_8));
        }
    }
}
