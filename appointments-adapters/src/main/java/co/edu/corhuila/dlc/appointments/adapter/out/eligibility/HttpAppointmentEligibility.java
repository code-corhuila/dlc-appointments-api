package co.edu.corhuila.dlc.appointments.adapter.out.eligibility;

import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * APT-001 through the owners' private contracts (internal-coordination.yaml): Patients
 * GET /internal/v1/patients/{id}/validation (patients:validate) and Auth GET /internal/v1/staff/{id}/validation
 * (identity:validate). 404 means not eligible; any other non-200 answer, a body outside the contract, a missing
 * credential or a deadline overrun throws DEPENDENCY_UNAVAILABLE, never a positive answer (fail closed).
 */
public final class HttpAppointmentEligibility implements AppointmentEligibility {
    private static final ObjectReader JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> STAFF_ROLES = Set.of("ADMINISTRATOR", "DENTIST", "SECRETARY_ASSISTANT");
    private final RestClient http;
    private final URI patientsOrigin;
    private final Supplier<String> patientsCredential;
    private final URI authOrigin;
    private final Supplier<String> authCredential;

    public HttpAppointmentEligibility(RestClient http, URI patientsOrigin, Supplier<String> patientsCredential,
            URI authOrigin, Supplier<String> authCredential) {
        this.http = Objects.requireNonNull(http, "http");
        this.patientsOrigin = Objects.requireNonNull(patientsOrigin, "patientsOrigin");
        this.patientsCredential = Objects.requireNonNull(patientsCredential, "patientsCredential");
        this.authOrigin = Objects.requireNonNull(authOrigin, "authOrigin");
        this.authCredential = Objects.requireNonNull(authCredential, "authCredential");
    }

    @Override
    public boolean isPatientActive(UUID patientId) {
        return validate(patientsOrigin.resolve("/internal/v1/patients/" + patientId + "/validation"),
                patientsCredential, body -> exactFields(body, "patientId", "active", "version")
                        && patientId.toString().equals(body.get("patientId").asText())
                        && body.get("version").canConvertToLong() && body.get("version").asLong() >= 1)
                .map(body -> body.get("active").booleanValue()).orElse(false);
    }

    @Override
    public boolean isDentistAuthorized(UUID dentistId) {
        return validate(authOrigin.resolve("/internal/v1/staff/" + dentistId + "/validation"), authCredential,
                body -> exactFields(body, "staffId", "active", "roles")
                        && dentistId.toString().equals(body.get("staffId").asText()) && knownRoles(body.get("roles")))
                .map(body -> body.get("active").booleanValue() && hasRole(body.get("roles"), "DENTIST"))
                .orElse(false);
    }

    /** Empty for 404; the validated body for 200; DEPENDENCY_UNAVAILABLE for everything else. */
    private Optional<JsonNode> validate(URI endpoint, Supplier<String> credential, Predicate<JsonNode> contract) {
        String token = credential.get();
        if (token == null || token.isBlank()) throw unavailable();
        try {
            return http.get().uri(endpoint)
                    .headers(headers -> {
                        headers.setBearerAuth(token);
                        String correlation = MDC.get("correlationId");
                        if (correlation != null) headers.set("X-Correlation-Id", correlation);
                    })
                    .accept(MediaType.APPLICATION_JSON)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 404) return Optional.<JsonNode>empty();
                        if (status != 200 || !MediaType.APPLICATION_JSON.equalsTypeAndSubtype(
                                response.getHeaders().getContentType())) {
                            throw unavailable();
                        }
                        JsonNode body = JSON.readValue(response.getBody());
                        if (body == null || !body.isObject() || !body.path("active").isBoolean()
                                || !contract.test(body)) {
                            throw unavailable();
                        }
                        return Optional.of(body);
                    });
        } catch (RestClientException | IllegalArgumentException failure) {
            // Never retain owner bodies or transport messages.
            throw unavailable();
        }
    }

    private static boolean exactFields(JsonNode body, String... names) {
        if (body.size() != names.length) return false;
        for (String name : names) {
            if (!body.has(name)) return false;
        }
        return body.get(names[0]).isTextual();
    }

    private static boolean knownRoles(JsonNode roles) {
        if (!roles.isArray()) return false;
        for (JsonNode role : roles) {
            if (!role.isTextual() || !STAFF_ROLES.contains(role.asText())) return false;
        }
        return true;
    }

    private static boolean hasRole(JsonNode roles, String role) {
        for (JsonNode value : roles) {
            if (role.equals(value.asText())) return true;
        }
        return false;
    }

    private static SchedulingException unavailable() {
        return new SchedulingException(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE);
    }
}
