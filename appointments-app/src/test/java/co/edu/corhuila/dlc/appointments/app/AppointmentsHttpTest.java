package co.edu.corhuila.dlc.appointments.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Annex C "Sobre de error y correlación" checks through the composed application. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AppointmentsHttpTest {
    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
    }

    @Test
    void protectedRouteWithoutTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/appointments"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void tokenWithAlgorithmOtherThanRs256IsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/appointments").header("Authorization", TestIdentity.unsigned(TestIdentity.SECRETARY)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/appointments").header("Authorization", TestIdentity.hs256(TestIdentity.SECRETARY)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/appointments").header("Authorization", TestIdentity.expired(TestIdentity.SECRETARY)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherKeyIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/appointments")
                        .header("Authorization", TestIdentity.signedWithOtherKey(TestIdentity.SECRETARY)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthAnswersWithoutTokenAndGeneratesCorrelationId() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
    }

    private static final String VALID_BODY = """
            {"patientId":"a9900000-0000-4000-8000-0000000000e1","dentistId":"a9900000-0000-4000-8000-0000000000d1",
             "startAt":"2031-05-01T09:00:00-05:00","endAt":"2031-05-01T09:30:00-05:00","reason":"Checkup"}""";

    private org.springframework.test.web.servlet.ResultActions schedule(String body, String key, String... roles)
            throws Exception {
        var request = post("/api/v1/appointments").contentType("application/json").content(body)
                .header("Authorization", TestIdentity.bearer(TestIdentity.SECRETARY,
                        roles.length == 0 ? new String[]{"SECRETARY_ASSISTANT"} : roles));
        return mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
    }

    @Test
    void scheduleWithoutIdempotencyKeyNamesTheHeaderInDetails() throws Exception {
        schedule(VALID_BODY, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[?(@.field == 'Idempotency-Key')]").exists());
        schedule(VALID_BODY, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'Idempotency-Key')]").exists());
    }

    @Test
    void invalidBodyNamesEveryInvalidFieldTogetherWithTheMissingHeader() throws Exception {
        schedule("{\"dentistId\":\"a9900000-0000-4000-8000-0000000000d1\",\"reason\":\"\"}", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[?(@.field == 'patientId')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'startAt')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'endAt')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'reason')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'Idempotency-Key')]").exists());
    }

    @Test
    void wronglyTypedFieldIsNamedInDetails() throws Exception {
        schedule(VALID_BODY.replace("a9900000-0000-4000-8000-0000000000e1", "not-a-uuid"), "retry-key-0001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("patientId"));
        schedule(VALID_BODY.replace("2031-05-01T09:00:00-05:00", "2031-05-01T09:00:00"), "retry-key-0001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("startAt"));
    }

    @Test
    void malformedJsonAndUnknownFieldsAreValidationErrors() throws Exception {
        schedule("{\"patientId\":", "retry-key-0001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        schedule(VALID_BODY.replace("\"reason\"", "\"createdBy\":\"x\",\"reason\""), "retry-key-0001")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("createdBy"));
    }

    @Test
    void calendarRejectsOutOfRangePagingUnknownFiltersAndBadRanges() throws Exception {
        String bearer = TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT");
        for (String query : new String[]{"limit=101", "limit=0", "page=0", "page=x", "status=PROGRAMADA",
                "from=2031-01-01T00:00:00", "from=2031-01-02T00:00:00Z&to=2031-01-01T00:00:00Z", "dentistId=nope"}) {
            mvc.perform(get("/api/v1/appointments?" + query).header("Authorization", bearer))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.details[0].field").isString());
        }
    }

    @Test
    void malformedAppointmentIdIsAValidationError() throws Exception {
        mvc.perform(get("/api/v1/appointments/not-a-uuid")
                        .header("Authorization", TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void dentistCannotScheduleAppointments() throws Exception {
        schedule(VALID_BODY, "retry-key-0001", "DENTIST")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void readinessReportsNotReadyWhileItsOwnDatabaseIsUnreachable() throws Exception {
        mvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("NOT_READY"));
    }

    @Test
    void receivedCorrelationIdIsReturned() throws Exception {
        mvc.perform(get("/health").header("X-Correlation-Id", "e2e-saga-1"))
                .andExpect(header().string("X-Correlation-Id", "e2e-saga-1"));
    }

    @Test
    void unsafeCorrelationIdIsReplacedByGeneratedOne() throws Exception {
        mvc.perform(get("/health").header("X-Correlation-Id", "bad value\r\ninjected"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
        mvc.perform(get("/health").header("X-Correlation-Id", "a".repeat(129)))
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void unknownRouteAnswersNotFoundEnvelopeWithTraceId() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").header("X-Correlation-Id", "trace-404")
                        .header("Authorization", TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT")))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "trace-404"))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.traceId").value("trace-404"));
    }

    @Test
    void unsupportedMethodAnswersEnvelope() throws Exception {
        mvc.perform(post("/health").header("X-Correlation-Id", "trace-405"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.traceId").value("trace-405"));
    }

    @Test
    void everyLogLineIsJsonWithTheCorrelationId(CapturedOutput output) throws Exception {
        mvc.perform(get("/health").header("X-Correlation-Id", "trace-log-1"));

        assertThat(output.getOut().lines().filter(line -> line.contains("trace-log-1")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line)
                        .startsWith("{")
                        .contains("\"correlationId\":\"trace-log-1\""));
    }
}
