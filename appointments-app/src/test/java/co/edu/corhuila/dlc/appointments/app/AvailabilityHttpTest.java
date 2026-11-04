package co.edu.corhuila.dlc.appointments.app;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GET/PUT /api/v1/dentists/{id}/availability (contract 1.4.0, decisions G1-G3) on real PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
class AvailabilityHttpTest {
    private static int day = (int) (Math.random() * 150_000);

    @Autowired
    private MockMvc mvc;

    @BeforeAll
    static void requireDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: availability HTTP tests skipped");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Test
    void firstReplacementCreatesVersionOneAndLaterOnesNeedTheCurrentVersion() throws Exception {
        String dentist = freshDentist();
        OffsetDateTime d = nextDay();
        read(dentist, secretary()).andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));

        replace(dentist, body(d, "08:00", "12:00", 1), secretary())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dentistId").value(dentist))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.intervals[0].startAt").value(d.withHour(8).toInstant().toString()))
                .andExpect(jsonPath("$.blockedIntervals.length()").value(0));
        read(dentist, secretary()).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        replace(dentist, body(d, "09:00", "12:00", 1), secretary())
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        replace(dentist, body(d, "09:00", "12:00", 1), secretary())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("STALE_VERSION"));
    }

    @Test
    void overlappingIntervalsAreReportedByPosition() throws Exception {
        OffsetDateTime d = nextDay();
        String body = "{\"intervals\":[" + interval(d, "08:00", "12:00") + "," + interval(d, "11:00", "13:00")
                + "],\"blockedIntervals\":[],\"expectedVersion\":1}";
        replace(freshDentist(), body, secretary())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("intervals[1]"));
    }

    @Test
    void replacementCannotLeaveABookedAppointmentUncovered() throws Exception {
        String dentist = freshDentist();
        OffsetDateTime d = nextDay();
        replace(dentist, body(d, "08:00", "12:00", 1), secretary()).andExpect(status().isOk());
        mvc.perform(post("/api/v1/appointments").contentType("application/json")
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()).header("Authorization", secretary())
                        .content("{\"patientId\":\"" + TestIdentity.ACTIVE_PATIENT + "\",\"dentistId\":\"" + dentist
                                + "\",\"startAt\":\"" + d.withHour(9) + "\",\"endAt\":\"" + d.withHour(9).plusMinutes(30)
                                + "\",\"reason\":\"Checkup\"}"))
                .andExpect(status().isCreated());

        replace(dentist, body(d, "10:00", "12:00", 1), secretary())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("APPOINTMENT_CONFLICT"));
        read(dentist, secretary()).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void rolesAndScopeDecideWhoReplacesAndReads() throws Exception {
        String dentist = freshDentist();
        OffsetDateTime d = nextDay();
        replace(dentist, body(d, "08:00", "12:00", 1), TestIdentity.bearer(dentist, "DENTIST"))
                .andExpect(status().isForbidden());
        replace(UUID.randomUUID().toString(), body(d, "08:00", "12:00", 1), secretary())
                .andExpect(status().isNotFound());
        replace(dentist, body(d, "08:00", "12:00", 1), TestIdentity.bearer(TestIdentity.SECRETARY, "ADMINISTRATOR"))
                .andExpect(status().isOk());
        read(dentist, TestIdentity.bearer(dentist, "DENTIST")).andExpect(status().isOk());
        read(dentist, TestIdentity.bearer(freshDentist(), "DENTIST")).andExpect(status().isNotFound());
    }

    private ResultActions read(String dentist, String bearer) throws Exception {
        return mvc.perform(get("/api/v1/dentists/" + dentist + "/availability").header("Authorization", bearer));
    }

    private ResultActions replace(String dentist, String body, String bearer) throws Exception {
        return mvc.perform(put("/api/v1/dentists/" + dentist + "/availability").contentType("application/json")
                .content(body).header("Authorization", bearer));
    }

    private static String secretary() {
        return TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT");
    }

    private static String freshDentist() {
        return TestIdentity.DENTIST_PREFIX + UUID.randomUUID().toString().substring(9);
    }

    private static OffsetDateTime nextDay() {
        return OffsetDateTime.parse("2033-01-01T00:00:00-05:00").plusDays(day++);
    }

    private static String body(OffsetDateTime d, String start, String end, long expectedVersion) {
        return "{\"intervals\":[" + interval(d, start, end) + "],\"blockedIntervals\":[],\"expectedVersion\":"
                + expectedVersion + "}";
    }

    private static String interval(OffsetDateTime d, String start, String end) {
        return "{\"startAt\":\"" + at(d, start) + "\",\"endAt\":\"" + at(d, end) + "\"}";
    }

    private static OffsetDateTime at(OffsetDateTime d, String time) {
        return d.withHour(Integer.parseInt(time.substring(0, 2))).withMinute(Integer.parseInt(time.substring(3)));
    }
}
