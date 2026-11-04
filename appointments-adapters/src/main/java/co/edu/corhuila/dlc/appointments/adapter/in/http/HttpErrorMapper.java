package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentConflictException;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.DentistNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidAvailabilityException;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.dlc.appointments.domain.model.ReminderOutcomeConflictException;
import co.edu.corhuila.dlc.appointments.domain.model.StaleVersionException;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidAppointmentException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Transport translation only; never serializes exception causes or unexpected messages. */
public final class HttpErrorMapper {
    private HttpErrorMapper() { }

    public static ResponseEntity<ApiError> map(Exception failure, String trustedTraceId) {
        if (failure instanceof RequestValidationException invalid) {
            return response(400, "VALIDATION_ERROR", "The request is invalid.", invalid.details(), trustedTraceId);
        }
        if (failure instanceof AppointmentNotFoundException || failure instanceof DentistNotFoundException
                || failure instanceof ClinicalAssignmentNotFoundException) {
            return response(404, "NOT_FOUND", "The resource does not exist.", null, trustedTraceId);
        }
        if (failure instanceof InvalidAvailabilityException invalid) {
            return response(400, "VALIDATION_ERROR", "The request is invalid.", invalid.errors().stream()
                    .map(e -> new ApiError.Detail(e.field(), e.message())).toList(), trustedTraceId);
        }
        if (failure instanceof StaleVersionException) {
            return response(409, "STALE_VERSION", "The resource has changed. Reload it before submitting again.",
                    null, trustedTraceId);
        }
        if (failure instanceof ReminderOutcomeConflictException outcome) {
            return outcome.reason() == ReminderOutcomeConflictException.Reason.STALE
                    ? response(409, "STALE_VERSION", "The delivery is not current for this appointment.", null,
                            trustedTraceId)
                    : response(409, "IDEMPOTENCY_CONFLICT", "A different outcome was already recorded.", null,
                            trustedTraceId);
        }
        if (failure instanceof InvalidStatusTransitionException) {
            return response(422, "INVALID_STATUS_TRANSITION", "The current state does not allow this transition.",
                    null, trustedTraceId);
        }
        if (failure instanceof AppointmentConflictException) {
            return response(409, "APPOINTMENT_CONFLICT",
                    "The requested change conflicts with the 24-hour rule or the appointment timing.", null,
                    trustedTraceId);
        }
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

    /** Names the offending JSON field for typing and unknown-field errors; never echoes the submitted value. */
    public static ApiError invalidBody(Throwable cause, String trustedTraceId) {
        if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()
                && mapping.getPath().get(0).getFieldName() != null) {
            String field = mapping.getPath().get(0).getFieldName();
            String message = cause instanceof UnrecognizedPropertyException
                    ? "Unknown field." : "Invalid value or format.";
            return new ApiError("VALIDATION_ERROR", "The request is invalid.",
                    List.of(new ApiError.Detail(field, message)), trustedTraceId);
        }
        return invalidRequest(trustedTraceId).getBody();
    }

    /** Envelope for statuses Spring MVC raises before any controller runs; empty when not covered. */
    public static Optional<ApiError> forFrameworkStatus(int status, String trustedTraceId) {
        return Optional.ofNullable(switch (status) {
            case 400 -> invalidRequest(trustedTraceId).getBody();
            case 404 -> new ApiError("NOT_FOUND", "The resource or route does not exist.", null, trustedTraceId);
            case 405 -> new ApiError("METHOD_NOT_ALLOWED", "The method is not allowed for this route.", null,
                    trustedTraceId);
            case 406 -> new ApiError("NOT_ACCEPTABLE", "The requested representation is not available.", null,
                    trustedTraceId);
            case 415 -> new ApiError("UNSUPPORTED_MEDIA_TYPE", "The content type is not supported.", null,
                    trustedTraceId);
            default -> null;
        });
    }

    private static ResponseEntity<ApiError> response(int status, String code, String message,
            List<ApiError.Detail> details, String trustedTraceId) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(code, message, details, trustedTraceId));
    }
}
