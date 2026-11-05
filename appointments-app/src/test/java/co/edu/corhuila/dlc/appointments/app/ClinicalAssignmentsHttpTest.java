package co.edu.corhuila.dlc.appointments.app;

import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/** HU-APT-004 clinical assignments over HTTP (contract 1.4.0) on PostgreSQL. */
@SpringBootTest
@AutoConfigureMockMvc
class ClinicalAssignmentsHttpTest {
    private static final String COLLECTION = "/api/v1/clinical-assignments";

    @Autowired
    private MockMvc mvc;

    @BeforeAll
    static void requireDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: assignment HTTP tests skipped");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Test
    void startCreatesWithLocationRetryReplaysAndASecondActivePairConflicts() throws Exception {
        String dentist = freshDentist();
        String key = "key-" + UUID.randomUUID();
        String created = start(TestIdentity.ACTIVE_PATIENT, dentist, key, secretary())
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith(COLLECTION + "/" + idOf(lastBody))))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.startedBy").value(TestIdentity.SECRETARY))
                .andExpect(jsonPath("$.endedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        start(TestIdentity.ACTIVE_PATIENT, dentist, key, secretary())
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(idOf(created)));
        start(TestIdentity.ACTIVE_PATIENT, dentist, "key-" + UUID.randomUUID(), secretary())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("APPOINTMENT_CONFLICT"));
    }

    @Test
    void ineligiblePatientDentistRoleAndSelfAssignmentAreRejected() throws Exception {
        String dentist = freshDentist();
        start(TestIdentity.INACTIVE_PATIENT, dentist, "key-" + UUID.randomUUID(), secretary())
                .andExpect(status().isConflict());
        start(TestIdentity.ACTIVE_PATIENT, dentist, "key-" + UUID.randomUUID(), TestIdentity.bearer(dentist, "DENTIST"))
                .andExpect(status().isForbidden());
        start(TestIdentity.ACTIVE_PATIENT, dentist, "key-" + UUID.randomUUID(),
                TestIdentity.bearer(dentist, "ADMINISTRATOR"))
                .andExpect(status().isForbidden());
        mvc.perform(post(COLLECTION).contentType("application/json").content("{}").header("Authorization", secretary()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'patientId')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'reason')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'Idempotency-Key')]").exists());
    }

    @Test
    void endingIsVersionedAuditedAndHappensOnce() throws Exception {
        String id = idOf(start(TestIdentity.ACTIVE_PATIENT, freshDentist(), "key-" + UUID.randomUUID(), secretary())
                .andReturn().getResponse().getContentAsString());
        end(id, "{\"reason\":\"Finished\",\"expectedVersion\":2}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("STALE_VERSION"));
        end(id, "{\"reason\":\"Finished\",\"expectedVersion\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.endedBy").value(TestIdentity.SECRETARY));
        end(id, "{\"reason\":\"Again\",\"expectedVersion\":2}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
        end(id, "{\"expectedVersion\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@.field == 'reason')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'expectedVersion')]").exists());
    }

    @Test
    void readsAreMetadataOnlyBoundedAndStaffOnly() throws Exception {
        String dentist = freshDentist();
        String id = idOf(start(TestIdentity.ACTIVE_PATIENT, dentist, "key-" + UUID.randomUUID(), secretary())
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get(COLLECTION + "/" + id).header("Authorization", secretary()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.dentistId").value(dentist));
        mvc.perform(get(COLLECTION + "/" + UUID.randomUUID()).header("Authorization", secretary()))
                .andExpect(status().isNotFound());
        mvc.perform(get(COLLECTION + "?dentistId=" + dentist).header("Authorization", secretary()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(id))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.meta.limit").value(20));
        for (String query : new String[]{"limit=101", "page=0", "status=ACTIVE", "dentistId=x"}) {
            mvc.perform(get(COLLECTION + "?" + query).header("Authorization", secretary()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get(COLLECTION).header("Authorization", TestIdentity.bearer(dentist, "DENTIST")))
                .andExpect(status().isForbidden());
    }

    private String lastBody = "";

    private ResultActions start(String patient, String dentist, String key, String bearer) throws Exception {
        ResultActions result = mvc.perform(post(COLLECTION).contentType("application/json")
                .header("Authorization", bearer).header("Idempotency-Key", key)
                .content("{\"patientId\":\"" + patient + "\",\"dentistId\":\"" + dentist + "\",\"reason\":\"Plan\"}"));
        lastBody = result.andReturn().getResponse().getContentAsString();
        return result;
    }

    private ResultActions end(String id, String body) throws Exception {
        return mvc.perform(post(COLLECTION + "/" + id + "/endings").contentType("application/json").content(body)
                .header("Authorization", secretary()).header("Idempotency-Key", "key-" + UUID.randomUUID()));
    }

    private static String idOf(String body) throws Exception {
        return body.isEmpty() ? "" : new ObjectMapper().readTree(body).path("id").asText();
    }

    private static String freshDentist() {
        return TestIdentity.DENTIST_PREFIX + UUID.randomUUID().toString().substring(9);
    }

    private static String secretary() {
        return TestIdentity.bearer(TestIdentity.SECRETARY, "SECRETARY_ASSISTANT");
    }
}
