package co.edu.corhuila.dlc.appointments.app;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** HU-APT-005 waiting list and HU-APT-006 type catalog over HTTP (contract 1.5.0) on PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
class WaitingListHttpTest {
    private static final String COLLECTION = "/api/v1/waiting-list-entries";
    private static final String SEEDED_APPOINTMENT = "a9900000-0000-4000-8000-000000000401";

    @Autowired
    private MockMvc mvc;

    @BeforeAll
    static void requireDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: waiting-list HTTP tests skipped");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Test
    void addsReplaysConflictsListsAndSchedulesAWaitingEntry() throws Exception {
        String dentist = TestIdentity.DENTIST;
        String key = "wait-" + UUID.randomUUID();
        String body = create(TestIdentity.ACTIVE_PATIENT, dentist, key)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.createdBy").value(TestIdentity.SECRETARY))
                .andExpect(jsonPath("$.appointmentId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = body.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        create(TestIdentity.ACTIVE_PATIENT, dentist, key).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        create(TestIdentity.ACTIVE_PATIENT, dentist, "wait-" + UUID.randomUUID())
                .andExpect(status().isConflict());

        mvc.perform(get(COLLECTION).header("Authorization", secretary()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == '" + id + "')]").exists())
                .andExpect(jsonPath("$.meta.page").value(1));

        resolve(id, "{\"outcome\":\"SCHEDULED\",\"appointmentId\":\"" + SEEDED_APPOINTMENT
                + "\",\"expectedVersion\":1}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.appointmentId").value(SEEDED_APPOINTMENT))
                .andExpect(jsonPath("$.version").value(2));
        resolve(id, "{\"outcome\":\"WITHDRAWN\",\"expectedVersion\":2}")
                .andExpect(status().is4xxClientError());
    }

    @Test
    void dentistsAreDeniedAndInvalidBodiesNameTheirFields() throws Exception {
        mvc.perform(get(COLLECTION).header("Authorization",
                        TestIdentity.bearer(TestIdentity.DENTIST, "DENTIST")))
                .andExpect(status().isForbidden());
        mvc.perform(post(COLLECTION).contentType("application/json").content("{}")
                        .header("Authorization", secretary()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'patientId')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'reason')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'Idempotency-Key')]").exists());
        resolve(UUID.randomUUID().toString(), "{\"outcome\":\"WITHDRAWN\",\"expectedVersion\":1}")
                .andExpect(status().isNotFound());
    }

    @Test
    void listsTheSeededAppointmentTypesInCatalogOrder() throws Exception {
        mvc.perform(get("/api/v1/appointment-types").header("Authorization",
                        TestIdentity.bearer(TestIdentity.DENTIST, "DENTIST")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("CONSULTA"))
                .andExpect(jsonPath("$.data[0].color").value("#2563EB"));
    }

    private ResultActions create(String patient, String dentist, String key) throws Exception {
        return mvc.perform(post(COLLECTION).contentType("application/json")
                .content("{\"patientId\":\"" + patient + "\",\"dentistId\":\"" + dentist
                        + "\",\"reason\":\"Limpieza pendiente\",\"preferredFrom\":\"2031-03-01\"}")
                .header("Idempotency-Key", key).header("Authorization", secretary()));
    }

    private ResultActions resolve(String id, String body) throws Exception {
        return mvc.perform(post(COLLECTION + "/" + id + "/resolutions").contentType("application/json")
                .content(body).header("Idempotency-Key", "res-" + UUID.randomUUID())
                .header("Authorization", secretary()));
    }

    private static String secretary() {
        return TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT");
    }
}
