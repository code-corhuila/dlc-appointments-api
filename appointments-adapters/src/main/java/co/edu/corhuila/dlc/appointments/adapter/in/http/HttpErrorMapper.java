package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidAppointmentException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Transport translation only; never serializes exception causes or unexpected messages. */
public final class HttpErrorMapper {
    private HttpErrorMapper() { }

    public static ResponseEntity<ApiError> map(Exception failure, String trustedTraceId) {
        if (failure instanceof InvalidAppointmentException invalid) {
            return response(400, "VALIDATION_ERROR", "The request is invalid.",
                    List.of(new ApiError.Detail(invalid.field(), invalid.getMessage())), trustedTraceId);
        }
        if (failure instanceof SchedulingException scheduling) {
            return switch (scheduling.reason()) {
                case PATIENT_INELIGIBLE, DENTIST_INELIGIBLE, SLOT_UNAVAILABLE ->
                    response(409, "APPOINTMENT_CONFLICT", "The patient or schedule state conflicts with the request.", null, trustedTraceId);
                case IDEMPOTENCY_CONFLICT ->
                    response(409, "IDEMPOTENCY_CONFLICT", "The key was already used with a different request.", null, trustedTraceId);
                case DEPENDENCY_UNAVAILABLE ->
                    response(503, "SERVICE_UNAVAILABLE", "The operation is temporarily unavailable.", null, trustedTraceId);
                case AUTHORIZATION_DENIED ->
                    response(403, "FORBIDDEN", "The operation is not permitted.", null, trustedTraceId);
            };
        }
        return response(500, "INTERNAL_ERROR", "An unexpected error occurred.", null, trustedTraceId);
    }

    public static ResponseEntity<ApiError> invalidRequest(String trustedTraceId) {
        return response(400, "VALIDATION_ERROR", "The request is invalid.", null, trustedTraceId);
    }

    private static ResponseEntity<ApiError> response(int status, String code, String message,
            List<ApiError.Detail> details, String trustedTraceId) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(code, message, details, trustedTraceId));
    }
}
