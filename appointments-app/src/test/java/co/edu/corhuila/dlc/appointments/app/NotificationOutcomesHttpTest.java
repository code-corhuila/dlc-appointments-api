package co.edu.corhuila.dlc.appointments.app;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** dlc-worker private contract (internal-coordination.yaml): reminder details and delivery outcomes. */
@SpringBootTest
@AutoConfigureMockMvc
class NotificationOutcomesHttpTest {
    private static final String WORKER = "dlc-worker";
    private static final String DETAILS = "appointments:notification-details:read";
    private static final String OUTCOMES = "appointments:notification-outcome:write";
    private static int day = (int) (Math.random() * 150_000);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcClient jdbc;

    @BeforeAll
    static void requireDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: worker contract tests skipped");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Test
    void theWorkerReadsMinimumDetailsAndReportsTheOutcomeOnce() throws Exception {
        String id = book();
        UUID delivery = UUID.randomUUID();
        jdbc.sql("UPDATE appointments.appointment_reminders SET status = 'REQUESTED', delivery_id = ? "
                + "WHERE appointment_id = ?::uuid").params(delivery, id).update();

        mvc.perform(get("/internal/v1/appointment-notifications/" + id + "/details")
                        .header("Authorization", TestIdentity.service(WORKER, DETAILS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId").value(id))
                .andExpect(jsonPath("$.patientId").value(TestIdentity.ACTIVE_PATIENT))
                .andExpect(jsonPath("$.appointmentVersion").value(1))
                .andExpect(jsonPath("$.status").value("PROGRAMADA"))
                .andExpect(jsonPath("$.startAt").isString())
                .andExpect(jsonPath("$.reason").doesNotExist());
        String sent = outcome(delivery, "SENT", ",\"providerMessageId\":\"provider-1\"");
        for (int i = 0; i < 2; i++) {
            report(id, sent).andExpect(status().isOk());
        }
        report(id, outcome(delivery, "DELIVERY_FAILED", ",\"failureCode\":\"SMTP_TIMEOUT\""))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidReportsUnknownDeliveriesAndWrongScopesAreRejected() throws Exception {
        String id = book();
        report(id, outcome(UUID.randomUUID(), "SENT", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'providerMessageId')]").exists());
        report(id, outcome(UUID.randomUUID(), "DELIVERY_FAILED", ",\"failureCode\":\"smtp down\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'failureCode')]").exists());
        report(id, "{\"status\":\"SENT\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'deliveryId')]").exists());
        report(id, outcome(UUID.randomUUID(), "SENT", ",\"providerMessageId\":\"p\"")).andExpect(status().isConflict());
        mvc.perform(get("/internal/v1/appointment-notifications/" + UUID.randomUUID() + "/details")
                        .header("Authorization", TestIdentity.service(WORKER, DETAILS)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/internal/v1/appointment-notifications/" + id + "/details")
                        .header("Authorization", TestIdentity.service(WORKER, OUTCOMES)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/v1/appointment-notifications/" + id + "/outcomes").contentType("application/json")
                        .content(outcome(UUID.randomUUID(), "SENT", ",\"providerMessageId\":\"p\""))
                        .header("Authorization", TestIdentity.service(WORKER, DETAILS)))
                .andExpect(status().isForbidden());
    }

    private ResultActions report(String id, String body) throws Exception {
        return mvc.perform(post("/internal/v1/appointment-notifications/" + id + "/outcomes")
                .contentType("application/json").content(body)
                .header("Authorization", TestIdentity.service(WORKER, OUTCOMES)));
    }

    private static String outcome(UUID delivery, String status, String extra) {
        return "{\"deliveryId\":\"" + delivery + "\",\"appointmentVersion\":1,\"notificationType\":\"REMINDER\","
                + "\"status\":\"" + status + "\",\"attempts\":1,\"occurredAt\":\"2031-01-01T10:00:00Z\"" + extra + "}";
    }

    private String book() throws Exception {
        String dentist = TestIdentity.DENTIST_PREFIX + UUID.randomUUID().toString().substring(9);
        OffsetDateTime start = OffsetDateTime.parse("2040-01-01T09:00:00-05:00").plusDays(day++);
        String staff = TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT");
        mvc.perform(put("/api/v1/dentists/" + dentist + "/availability").contentType("application/json")
                        .header("Authorization", staff)
                        .content("{\"intervals\":[{\"startAt\":\"" + start.minusHours(1) + "\",\"endAt\":\""
                                + start.plusHours(2) + "\"}],\"blockedIntervals\":[],\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        String created = mvc.perform(post("/api/v1/appointments").contentType("application/json")
                        .header("Authorization", staff).header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .content("{\"patientId\":\"" + TestIdentity.ACTIVE_PATIENT + "\",\"dentistId\":\"" + dentist
                                + "\",\"startAt\":\"" + start + "\",\"endAt\":\"" + start.plusMinutes(30)
                                + "\",\"reason\":\"Checkup\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(created).get("id").asText();
    }
}
