package co.edu.corhuila.dlc.appointments.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Annex C "Creación idempotente" and "Representación" for POST /api/v1/appointments, through the composed
 * application against PostgreSQL migrated by dlc-appointments-db. Skipped without TEST_DATABASE_URL.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppointmentsSchedulingHttpTest {
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String UTC_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z";
    // Fresh days per JVM so repeated runs against the same database never reuse an availability interval.
    private static int day = (int) (Math.random() * 150_000);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;

    @BeforeAll
    static void requireDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: HTTP scheduling tests skipped");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Test
    void firstSubmissionCreatesWithLocationAndRetryReturnsTheSameId() throws Exception {
        String body = body(TestIdentity.ACTIVE_PATIENT, openDay(), "09:00");
        String key = "key-" + UUID.randomUUID();

        String created = schedule(body, key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(matchesPattern(UUID_PATTERN)))
                .andReturn().getResponse().getContentAsString();
        String id = new ObjectMapper().readTree(created).get("id").asText();
        schedule(body, key).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        schedule(body, "key-" + UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("APPOINTMENT_CONFLICT"));
    }

    @Test
    void createdResourceHasItsLocationAndTheContractRepresentation() throws Exception {
        OffsetDateTime dayStart = openDay();
        var result = schedule(body(TestIdentity.ACTIVE_PATIENT, dayStart, "10:00"), "key-" + UUID.randomUUID())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern("/api/v1/appointments/" + UUID_PATTERN)))
                .andReturn();
        JsonNode json = new ObjectMapper().readTree(result.getResponse().getContentAsString());

        assertThat(result.getResponse().getHeader("Location")).endsWith(json.get("id").asText());
        var fields = new java.util.HashSet<String>();
        json.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "patientId",
                "dentistId", "startAt", "endAt", "reason", "status", "confirmationStatus", "reminderDueAt",
                "createdAt", "updatedAt", "version");
        assertThat(json.get("status").asText()).isEqualTo("PROGRAMADA");
        assertThat(json.get("confirmationStatus").asText()).isEqualTo("PENDING");
        assertThat(json.get("version").isIntegralNumber()).isTrue();
        assertThat(json.get("version").asLong()).isEqualTo(1);
        for (String instant : new String[]{"startAt", "endAt", "reminderDueAt", "createdAt", "updatedAt"}) {
            assertThat(json.get(instant).asText()).matches(UTC_PATTERN);
        }
        assertThat(Instant.parse(json.get("startAt").asText()))
                .isEqualTo(dayStart.withHour(10).toInstant());
        assertThat(Instant.parse(json.get("reminderDueAt").asText()))
                .isEqualTo(dayStart.withHour(10).minusHours(24).toInstant());
    }

    @Test
    void sameKeyWithAnotherPayloadIsAnIdempotencyConflict() throws Exception {
        OffsetDateTime dayStart = openDay();
        String key = "key-" + UUID.randomUUID();
        schedule(body(TestIdentity.ACTIVE_PATIENT, dayStart, "09:00"), key).andExpect(status().isCreated());
        schedule(body(TestIdentity.ACTIVE_PATIENT, dayStart, "11:00"), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void inactivePatientOrUnavailableSlotIsAConflict() throws Exception {
        OffsetDateTime dayStart = openDay();
        schedule(body(TestIdentity.INACTIVE_PATIENT, dayStart, "09:00"), "key-" + UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("APPOINTMENT_CONFLICT"));
        schedule(body(TestIdentity.ACTIVE_PATIENT, dayStart, "13:00"), "key-" + UUID.randomUUID())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("APPOINTMENT_CONFLICT"));
    }

    @Test
    void unverifiablePatientFailsClosedWithServiceUnavailable() throws Exception {
        schedule(body(TestIdentity.UNVERIFIABLE_PATIENT, openDay(), "09:00"), "key-" + UUID.randomUUID())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void readReturnsTheRepresentationWithinTheReadersScope() throws Exception {
        String created = schedule(body(TestIdentity.ACTIVE_PATIENT, openDay(), "11:00"), "key-" + UUID.randomUUID())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = new ObjectMapper().readTree(created).get("id").asText();

        read(id, TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PROGRAMADA"));
        read(id, TestIdentity.bearer(TestIdentity.DENTIST, "DENTIST"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dentistId").value(TestIdentity.DENTIST));
        read(id, TestIdentity.bearer(UUID.randomUUID().toString(), "DENTIST"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        read(UUID.randomUUID().toString(), TestIdentity.bearer(TestIdentity.SECRETARY, "ADMINISTRATOR"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void calendarPagesMostRecentFirstWithinFiltersAndScope() throws Exception {
        String dentist = TestIdentity.DENTIST_PREFIX + UUID.randomUUID().toString().substring(9);
        OffsetDateTime dayStart = openDay(dentist);
        for (String time : new String[]{"08:00", "09:00", "10:00"}) {
            schedule(body(TestIdentity.ACTIVE_PATIENT, dentist, dayStart, time), "key-" + UUID.randomUUID())
                    .andExpect(status().isCreated());
        }
        String secretary = TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT");

        String json = list("dentistId=" + dentist, secretary)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.meta.page").value(1))
                .andExpect(jsonPath("$.meta.limit").value(20))
                .andExpect(jsonPath("$.meta.total").value(3))
                .andExpect(jsonPath("$.meta.totalPages").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = new ObjectMapper().readTree(json).get("data");
        for (int i = 1; i < data.size(); i++) {
            assertThat(Instant.parse(data.get(i - 1).get("createdAt").asText()))
                    .isAfterOrEqualTo(Instant.parse(data.get(i).get("createdAt").asText()));
        }
        assertThat(data.get(0).get("startAt").asText()).isEqualTo(dayStart.withHour(10).toInstant().toString());

        list("dentistId=" + dentist + "&limit=2&page=2", secretary)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.page").value(2))
                .andExpect(jsonPath("$.meta.limit").value(2))
                .andExpect(jsonPath("$.meta.totalPages").value(2));
        String from = dayStart.withHour(9).toInstant().toString();
        String to = dayStart.withHour(10).toInstant().toString();
        list("dentistId=" + dentist + "&from=" + from + "&to=" + to, secretary)
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].startAt").value(from));
        list("dentistId=" + dentist + "&from=" + from + "&to=" + from, secretary)
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(0));
        list("", TestIdentity.bearer(dentist, "DENTIST"))
                .andExpect(jsonPath("$.meta.total").value(3));
        list("dentistId=" + TestIdentity.DENTIST, TestIdentity.bearer(dentist, "DENTIST"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.meta.total").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private ResultActions list(String query, String bearer) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/appointments?" + query).header("Authorization", bearer));
    }

    private ResultActions read(String id, String bearer) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/appointments/" + id).header("Authorization", bearer));
    }

    private ResultActions schedule(String body, String key) throws Exception {
        return mvc.perform(post("/api/v1/appointments").contentType("application/json").content(body)
                .header("Idempotency-Key", key)
                .header("Authorization", TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT")));
    }

    /** A fresh day with TestIdentity.DENTIST available 08:00-12:00 Bogota time and no appointments yet. */
    private OffsetDateTime openDay() {
        return openDay(TestIdentity.DENTIST);
    }

    private OffsetDateTime openDay(String dentistId) {
        OffsetDateTime dayStart = OffsetDateTime.parse("2032-01-01T00:00:00-05:00")
                .plusDays(day++);
        UUID dentist = UUID.fromString(dentistId);
        UUID schedule = jdbc.sql("SELECT schedule_id FROM appointments.dentist_schedules WHERE dentist_id = ?")
                .param(dentist).query(UUID.class).optional().orElseGet(() -> {
                    UUID created = UUID.randomUUID();
                    Timestamp now = Timestamp.from(Instant.now());
                    jdbc.sql("INSERT INTO appointments.dentist_schedules VALUES (?, ?, 1, ?, ?, ?, ?)")
                            .params(created, dentist, now, dentist, now, dentist).update();
                    return created;
                });
        jdbc.sql("INSERT INTO appointments.availability_intervals VALUES (?, ?, ?, ?)").params(UUID.randomUUID(),
                schedule, Timestamp.from(dayStart.withHour(8).toInstant()),
                Timestamp.from(dayStart.withHour(12).toInstant())).update();
        return dayStart;
    }

    private static String body(String patient, OffsetDateTime dayStart, String time) {
        return body(patient, TestIdentity.DENTIST, dayStart, time);
    }

    private static String body(String patient, String dentist, OffsetDateTime dayStart, String time) {
        OffsetDateTime start = dayStart.withHour(Integer.parseInt(time.substring(0, 2)));
        return "{\"patientId\":\"" + patient + "\",\"dentistId\":\"" + dentist + "\",\"startAt\":\""
                + start + "\",\"endAt\":\"" + start.plusMinutes(30) + "\",\"reason\":\"Checkup\"}";
    }
}
