package co.edu.corhuila.dlc.appointments.adapter.out.eligibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class HttpAppointmentEligibilityTest {
    private static final URI PATIENTS = URI.create("http://patients.test");
    private static final URI AUTH = URI.create("http://auth.test");
    private static final UUID PATIENT = UUID.fromString("a9900000-0000-4000-8000-0000000000e1");
    private static final UUID DENTIST = UUID.fromString("a9900000-0000-4000-8000-0000000000d1");
    private MockRestServiceServer server;
    private HttpAppointmentEligibility eligibility;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        eligibility = new HttpAppointmentEligibility(builder.build(), PATIENTS, () -> "to-patients", AUTH,
                () -> "to-auth");
        MDC.put("correlationId", "trace-eligibility");
    }

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void activePatientIsEligibleThroughTheOwnerContractWithServiceCredentialAndCorrelation() {
        server.expect(requestTo(PATIENTS + "/internal/v1/patients/" + PATIENT + "/validation"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer to-patients"))
                .andExpect(header("X-Correlation-Id", "trace-eligibility"))
                .andRespond(withSuccess(patient(PATIENT, true), MediaType.APPLICATION_JSON));
        assertTrue(eligibility.isPatientActive(PATIENT));
        server.verify();
    }

    @Test
    void inactiveOrUnknownPatientIsNotEligible() {
        server.expect(requestTo(PATIENTS + "/internal/v1/patients/" + PATIENT + "/validation"))
                .andRespond(withSuccess(patient(PATIENT, false), MediaType.APPLICATION_JSON));
        assertFalse(eligibility.isPatientActive(PATIENT));
        server.reset();
        server.expect(requestTo(PATIENTS + "/internal/v1/patients/" + PATIENT + "/validation"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertFalse(eligibility.isPatientActive(PATIENT));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 409, 500, 503})
    void anyOtherPatientsAnswerFailsClosed(int status) {
        server.expect(requestTo(PATIENTS + "/internal/v1/patients/" + PATIENT + "/validation"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));
        assertUnavailable(() -> eligibility.isPatientActive(PATIENT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"patientId\":\"a9900000-0000-4000-8000-0000000000e2\",\"active\":true,\"version\":1}",
            "{\"patientId\":\"a9900000-0000-4000-8000-0000000000e1\",\"active\":\"yes\",\"version\":1}",
            "{\"patientId\":\"a9900000-0000-4000-8000-0000000000e1\",\"active\":true}",
            "{\"patientId\":\"a9900000-0000-4000-8000-0000000000e1\",\"active\":true,\"version\":1,\"name\":\"x\"}",
            "not-json"})
    void patientsAnswerOutsideTheContractFailsClosed(String body) {
        server.expect(requestTo(PATIENTS + "/internal/v1/patients/" + PATIENT + "/validation"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertUnavailable(() -> eligibility.isPatientActive(PATIENT));
    }

    @Test
    void activeStaffWithTheDentistRoleIsAuthorized() {
        server.expect(requestTo(AUTH + "/internal/v1/staff/" + DENTIST + "/validation"))
                .andExpect(header("Authorization", "Bearer to-auth"))
                .andRespond(withSuccess(staff(DENTIST, true, "\"DENTIST\""), MediaType.APPLICATION_JSON));
        assertTrue(eligibility.isDentistAuthorized(DENTIST));
        server.verify();
    }

    @Test
    void inactiveStaffStaffWithoutDentistRoleOrUnknownStaffIsNotAuthorized() {
        for (String body : new String[]{staff(DENTIST, false, "\"DENTIST\""),
                staff(DENTIST, true, "\"SECRETARY_ASSISTANT\",\"ADMINISTRATOR\"")}) {
            server.reset();
            server.expect(requestTo(AUTH + "/internal/v1/staff/" + DENTIST + "/validation"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertFalse(eligibility.isDentistAuthorized(DENTIST));
        }
        server.reset();
        server.expect(requestTo(AUTH + "/internal/v1/staff/" + DENTIST + "/validation"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertFalse(eligibility.isDentistAuthorized(DENTIST));
    }

    @Test
    void staffAnswerForAnotherIdentityOrServerErrorFailsClosed() {
        server.expect(requestTo(AUTH + "/internal/v1/staff/" + DENTIST + "/validation"))
                .andRespond(withSuccess(staff(PATIENT, true, "\"DENTIST\""), MediaType.APPLICATION_JSON));
        assertUnavailable(() -> eligibility.isDentistAuthorized(DENTIST));
        server.reset();
        server.expect(requestTo(AUTH + "/internal/v1/staff/" + DENTIST + "/validation"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertUnavailable(() -> eligibility.isDentistAuthorized(DENTIST));
    }

    @Test
    void missingServiceCredentialFailsClosedWithoutCallingTheOwner() {
        var withoutCredential = new HttpAppointmentEligibility(RestClient.create(), PATIENTS, () -> null, AUTH,
                () -> " ");
        assertUnavailable(() -> withoutCredential.isPatientActive(PATIENT));
        assertUnavailable(() -> withoutCredential.isDentistAuthorized(DENTIST));
    }

    @Test
    void ownerThatDoesNotAnswerWithinTheDeadlineFailsClosed() throws Exception {
        HttpServer slow = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        slow.createContext("/", exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        slow.start();
        try {
            var requests = new JdkClientHttpRequestFactory(HttpClient.newHttpClient());
            requests.setReadTimeout(Duration.ofMillis(200));
            URI origin = URI.create("http://localhost:" + slow.getAddress().getPort());
            var timed = new HttpAppointmentEligibility(RestClient.builder().requestFactory(requests).build(),
                    origin, () -> "to-patients", origin, () -> "to-auth");
            assertUnavailable(() -> timed.isPatientActive(PATIENT));
        } finally {
            slow.stop(0);
        }
    }

    private static void assertUnavailable(Runnable call) {
        assertEquals(SchedulingException.Reason.DEPENDENCY_UNAVAILABLE,
                assertThrows(SchedulingException.class, call::run).reason());
    }

    private static String patient(UUID id, boolean active) {
        return "{\"patientId\":\"" + id + "\",\"active\":" + active + ",\"version\":3}";
    }

    private static String staff(UUID id, boolean active, String roles) {
        return "{\"staffId\":\"" + id + "\",\"active\":" + active + ",\"roles\":[" + roles + "]}";
    }
}
