package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidAppointmentException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HttpErrorMapperTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void serializesOnlyRequiredFieldsWhenOptionalValuesAreAbsent() throws Exception {
        var tree = json.readTree(json.writeValueAsString(new ApiError("INTERNAL_ERROR", "Safe message", null, null)));
        assertEquals(2, tree.size());
        assertEquals("INTERNAL_ERROR", tree.get("error").textValue());
        assertEquals("Safe message", tree.get("message").textValue());
    }

    @Test
    void serializesFieldDetailsAndExplicitTrustedTrace() throws Exception {
        var error = new ApiError("VALIDATION_ERROR", "Invalid request",
                List.of(new ApiError.Detail("reason", "Required")), "trusted-trace");
        var tree = json.readTree(json.writeValueAsString(error));
        assertEquals(4, tree.size());
        assertEquals("reason", tree.at("/details/0/field").textValue());
        assertEquals("Required", tree.at("/details/0/message").textValue());
        assertEquals("trusted-trace", tree.get("traceId").textValue());
    }

    @Test
    void mapsAllSixSchedulingReasonsToApprovedStatusesAndCodes() {
        var expected = Map.of(
                "PATIENT_INELIGIBLE", Map.entry(409, "APPOINTMENT_CONFLICT"),
                "DENTIST_INELIGIBLE", Map.entry(409, "APPOINTMENT_CONFLICT"),
                "SLOT_UNAVAILABLE", Map.entry(409, "APPOINTMENT_CONFLICT"),
                "DEPENDENCY_UNAVAILABLE", Map.entry(503, "SERVICE_UNAVAILABLE"),
                "AUTHORIZATION_DENIED", Map.entry(403, "FORBIDDEN"),
                "IDEMPOTENCY_CONFLICT", Map.entry(409, "IDEMPOTENCY_CONFLICT"));
        assertEquals(expected.size(), SchedulingException.Reason.values().length);
        for (var reason : SchedulingException.Reason.values()) {
            var response = HttpErrorMapper.map(new SchedulingException(reason), "trusted-trace");
            assertEquals(expected.get(reason.name()).getKey(), response.getStatusCode().value());
            assertEquals(expected.get(reason.name()).getValue(), response.getBody().error());
            assertEquals("trusted-trace", response.getBody().traceId());
        }
    }

    @Test
    void mapsStructuralDomainErrorToValidationDetail() {
        var response = HttpErrorMapper.map(new InvalidAppointmentException("endAt", "End must be after start."), null);
        assertEquals(400, response.getStatusCode().value());
        assertEquals("VALIDATION_ERROR", response.getBody().error());
        assertEquals(new ApiError.Detail("endAt", "End must be after start."), response.getBody().details().getFirst());
    }

    @Test
    void unexpectedExceptionsNeverExposeInternalMessagesOrBecomeClientErrors() throws Exception {
        for (var failure : List.of(new RuntimeException("secret-sql"), new IllegalArgumentException("secret-sql"),
                new NullPointerException("secret-sql"))) {
            var response = HttpErrorMapper.map(failure, null);
            assertEquals(500, response.getStatusCode().value());
            assertEquals("INTERNAL_ERROR", response.getBody().error());
            assertEquals("An unexpected error occurred.", response.getBody().message());
            assertFalse(json.writeValueAsString(response.getBody()).contains("secret-sql"));
        }
    }
}
