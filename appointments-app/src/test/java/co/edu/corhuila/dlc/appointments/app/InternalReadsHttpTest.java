package co.edu.corhuila.dlc.appointments.app;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** #16 internal reads for Clinical (internal-coordination.yaml), service-authenticated with per-operation scopes. */
@SpringBootTest
@AutoConfigureMockMvc
class InternalReadsHttpTest {
    private static final String CLINICAL = "dlc-clinical-api";

    @Autowired
    private MockMvc mvc;

    @BeforeAll
    static void requireDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: internal read tests skipped");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Test
    void pairValidationAndDentistListReflectAnOngoingAssignment() throws Exception {
        String dentist = TestIdentity.DENTIST_PREFIX + UUID.randomUUID().toString().substring(9);
        mvc.perform(post("/api/v1/clinical-assignments").contentType("application/json")
                        .header("Authorization", TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT"))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .content("{\"patientId\":\"" + TestIdentity.ACTIVE_PATIENT + "\",\"dentistId\":\"" + dentist
                                + "\",\"reason\":\"Plan\"}"))
                .andExpect(status().isCreated());

        read("/internal/v1/patients/" + TestIdentity.ACTIVE_PATIENT + "/assignments?dentistId=" + dentist,
                "assignments:read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").value(true))
                .andExpect(jsonPath("$.sourceTypes[0]").value("ONGOING_ASSIGNMENT"))
                .andExpect(jsonPath("$.relationshipVersion").isNumber());
        read("/internal/v1/dentists/" + dentist + "/assigned-patients", "assignments:read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dentistId").value(dentist))
                .andExpect(jsonPath("$.assignmentsVersion").isNumber())
                .andExpect(jsonPath("$.data[0].patientId").value(TestIdentity.ACTIVE_PATIENT))
                .andExpect(jsonPath("$.meta.total").value(1));
        read("/internal/v1/dentists/" + UUID.randomUUID() + "/assigned-patients", "assignments:read")
                .andExpect(status().isNotFound());
        read("/internal/v1/patients/" + TestIdentity.ACTIVE_PATIENT + "/assignments", "assignments:read")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("dentistId"));
        read("/internal/v1/dentists/" + dentist + "/assigned-patients?limit=101", "assignments:read")
                .andExpect(status().isBadRequest());
    }

    @Test
    void encounterStatusIsTheAuthoritativeAppointmentStateAndEveryReadNeedsItsScope() throws Exception {
        String dentist = TestIdentity.DENTIST_PREFIX + UUID.randomUUID().toString().substring(9);
        String start = "2039-03-0" + (1 + (int) (Math.random() * 8)) + "T09:00:00-05:00";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/dentists/" + dentist + "/availability").contentType("application/json")
                        .header("Authorization", TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT"))
                        .content("{\"intervals\":[{\"startAt\":\"" + start.replace("09:00", "08:00") + "\",\"endAt\":\""
                                + start.replace("09:00", "12:00") + "\"}],\"blockedIntervals\":[],\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        String created = mvc.perform(post("/api/v1/appointments").contentType("application/json")
                        .header("Authorization", TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT"))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .content("{\"patientId\":\"" + TestIdentity.ACTIVE_PATIENT + "\",\"dentistId\":\"" + dentist
                                + "\",\"startAt\":\"" + start + "\",\"endAt\":\"" + start.replace("09:00", "09:30")
                                + "\",\"reason\":\"Checkup\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = new ObjectMapper().readTree(created).get("id").asText();

        read("/internal/v1/appointments/" + id + "/encounter-status", "appointments:encounter-status:read")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId").value(id))
                .andExpect(jsonPath("$.dentistId").value(dentist))
                .andExpect(jsonPath("$.status").value("PROGRAMADA"))
                .andExpect(jsonPath("$.version").value(1));
        read("/internal/v1/appointments/" + UUID.randomUUID() + "/encounter-status",
                "appointments:encounter-status:read")
                .andExpect(status().isNotFound());
        read("/internal/v1/appointments/" + id + "/encounter-status", "assignments:read")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        read("/internal/v1/dentists/" + dentist + "/assigned-patients", "appointments:encounter-status:read")
                .andExpect(status().isForbidden());
    }

    private ResultActions read(String path, String scope) throws Exception {
        return mvc.perform(get(path).header("Authorization", TestIdentity.service(CLINICAL, scope)));
    }
}
