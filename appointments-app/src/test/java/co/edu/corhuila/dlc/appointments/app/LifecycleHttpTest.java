package co.edu.corhuila.dlc.appointments.app;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

/** HU-APT-002 confirmations, cancellations, attention starts and no-shows (contract 1.4.0) on PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
class LifecycleHttpTest {
    private static int day = (int) (Math.random() * 150_000);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @BeforeAll
    static void requireDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: lifecycle HTTP tests skipped");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Test
    void confirmationAnswersTheNewVersionAndASecondOneIsAnInvalidTransition() throws Exception {
        String id = book(farStart());
        call(id, "confirmations", "{\"expectedVersion\":1,\"channel\":\"PHONE\"}", secretary())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMADA"))
                .andExpect(jsonPath("$.confirmationStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.version").value(2));
        call(id, "confirmations", "{\"expectedVersion\":2,\"channel\":\"PHONE\"}", secretary())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
        call(id, "confirmations", "{\"expectedVersion\":1,\"channel\":\"EMAIL\"}", secretary())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STALE_VERSION"));
    }

    @Test
    void cancellingTwiceAnswersTheSameCancelledRepresentation() throws Exception {
        String id = book(farStart());
        for (int i = 0; i < 2; i++) {
            call(id, "cancellations", "{\"expectedVersion\":1,\"reason\":\"Patient travels\"}", secretary())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELADA"))
                    .andExpect(jsonPath("$.version").value(2));
        }
    }

    @Test
    void insideTwentyFourHoursOnlyAnAdministratorOverrideCancels() throws Exception {
        String id = book(OffsetDateTime.now(ZoneOffset.UTC).plusHours(3).truncatedTo(ChronoUnit.HOURS));
        call(id, "cancellations", "{\"expectedVersion\":1,\"reason\":\"Late\"}", secretary())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("APPOINTMENT_CONFLICT"));
        call(id, "cancellations", "{\"expectedVersion\":1,\"reason\":\"Late\",\"override\":true}", secretary())
                .andExpect(status().isForbidden());
        call(id, "cancellations", "{\"expectedVersion\":1,\"reason\":\"Emergency\",\"override\":true}",
                TestIdentity.bearer(TestIdentity.SECRETARY, "ADMINISTRATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELADA"));
    }

    @Test
    void attentionStartsFromConfirmadaAndNoShowWaitsForTheEnd() throws Exception {
        String id = book(farStart());
        call(id, "attention-starts", "{\"expectedVersion\":1}", secretary())
                .andExpect(status().isUnprocessableEntity());
        call(id, "no-shows", "{\"expectedVersion\":1,\"reason\":\"Absent\"}", secretary())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("APPOINTMENT_CONFLICT"));
        call(id, "confirmations", "{\"expectedVersion\":1,\"channel\":\"IN_PERSON\"}", secretary())
                .andExpect(status().isOk());
        call(id, "attention-starts", "{\"expectedVersion\":2}", secretary())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EN_ATENCION"));
    }

    @Test
    void reschedulingMovesTheAppointmentBackToProgramadaWithinTheDentistAvailability() throws Exception {
        OffsetDateTime start = farStart();
        String id = book(start);
        call(id, "confirmations", "{\"expectedVersion\":1,\"channel\":\"PHONE\"}", secretary())
                .andExpect(status().isOk());
        OffsetDateTime shifted = start.plusMinutes(15);
        call(id, "reschedulings", reschedule(2, shifted, "Overlaps only itself"), secretary())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROGRAMADA"))
                .andExpect(jsonPath("$.confirmationStatus").value("PENDING"))
                .andExpect(jsonPath("$.startAt").value(shifted.toInstant().toString()))
                .andExpect(jsonPath("$.version").value(3));
        call(id, "reschedulings", reschedule(3, start.plusHours(5), "Outside availability"), secretary())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("APPOINTMENT_CONFLICT"));
        call(id, "reschedulings", reschedule(3, shifted, "Same interval"), secretary())
                .andExpect(status().isBadRequest());
        call(id, "reschedulings", "{\"expectedVersion\":3}", secretary())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'startAt')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'endAt')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'reason')]").exists());
    }

    private static String reschedule(long version, OffsetDateTime start, String reason) {
        return "{\"expectedVersion\":" + version + ",\"startAt\":\"" + start + "\",\"endAt\":\""
                + start.plusMinutes(30) + "\",\"reason\":\"" + reason + "\"}";
    }

    @Test
    void completionWaitsForTheDentistDeclarationRecordedFromClinical() throws Exception {
        String id = book(farStart());
        call(id, "completions", "{\"expectedVersion\":1}", secretary())
                .andExpect(status().isUnprocessableEntity());
        call(id, "confirmations", "{\"expectedVersion\":1,\"channel\":\"PHONE\"}", secretary())
                .andExpect(status().isOk());
        call(id, "attention-starts", "{\"expectedVersion\":2}", secretary()).andExpect(status().isOk());
        call(id, "completions", "{\"expectedVersion\":3}", secretary())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("APPOINTMENT_CONFLICT"));

        jdbc.sql("""
                INSERT INTO appointments.care_completions (appointment_id, event_id, clinical_record_id, consultation_id,
                    patient_id, dentist_id, completed_at, clinical_version, recorded_at)
                SELECT appointment_id, gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), patient_id, dentist_id,
                    now(), 1, now() FROM appointments.appointments WHERE appointment_id = ?::uuid""").param(id).update();
        call(id, "completions", "{\"expectedVersion\":3}", TestIdentity.bearer(TestIdentity.DENTIST, "DENTIST"))
                .andExpect(status().isForbidden());
        call(id, "completions", "{\"expectedVersion\":3}", secretary())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FINALIZADA"))
                .andExpect(jsonPath("$.version").value(4));
    }

    @Test
    void dentistUnknownAppointmentAndInvalidRequestsAreRejected() throws Exception {
        String id = book(farStart());
        call(id, "confirmations", "{\"expectedVersion\":1,\"channel\":\"PHONE\"}",
                TestIdentity.bearer(TestIdentity.DENTIST, "DENTIST"))
                .andExpect(status().isForbidden());
        call(UUID.randomUUID().toString(), "confirmations", "{\"expectedVersion\":1,\"channel\":\"PHONE\"}",
                secretary())
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/appointments/" + id + "/confirmations").contentType("application/json")
                        .content("{}").header("Authorization", secretary()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'expectedVersion')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'channel')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'Idempotency-Key')]").exists());
        call(id, "confirmations", "{\"expectedVersion\":1,\"channel\":\"PHONE\",\"reason\":\"x\"}", secretary())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("reason"));
        call(id, "cancellations", "{\"expectedVersion\":0,\"reason\":\" \"}", secretary())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'expectedVersion')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'reason')]").exists());
    }

    private ResultActions call(String id, String operation, String body, String bearer) throws Exception {
        return mvc.perform(post("/api/v1/appointments/" + id + "/" + operation).contentType("application/json")
                .content(body).header("Authorization", bearer).header("Idempotency-Key", "key-" + UUID.randomUUID()));
    }

    /** A fresh Dentist available around {@code start}, and one appointment booked there. */
    private String book(OffsetDateTime start) throws Exception {
        String dentist = TestIdentity.DENTIST_PREFIX + UUID.randomUUID().toString().substring(9);
        mvc.perform(put("/api/v1/dentists/" + dentist + "/availability").contentType("application/json")
                        .header("Authorization", secretary())
                        .content("{\"intervals\":[{\"startAt\":\"" + start.minusHours(1) + "\",\"endAt\":\""
                                + start.plusHours(2) + "\"}],\"blockedIntervals\":[],\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        String created = mvc.perform(post("/api/v1/appointments").contentType("application/json")
                        .header("Authorization", secretary()).header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .content("{\"patientId\":\"" + TestIdentity.ACTIVE_PATIENT + "\",\"dentistId\":\"" + dentist
                                + "\",\"startAt\":\"" + start + "\",\"endAt\":\"" + start.plusMinutes(30)
                                + "\",\"reason\":\"Checkup\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(created).get("id").asText();
    }

    private static OffsetDateTime farStart() {
        return OffsetDateTime.parse("2036-01-01T09:00:00-05:00").plusDays(day++);
    }

    private static String secretary() {
        return TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT");
    }
}
