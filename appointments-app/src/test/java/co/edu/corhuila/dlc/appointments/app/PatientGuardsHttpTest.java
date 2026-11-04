package co.edu.corhuila.dlc.appointments.app;

import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
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

/** #17 ADR-017 guard operations (internal-coordination.yaml), scope patient-gates:coordinate. */
@SpringBootTest
@AutoConfigureMockMvc
class PatientGuardsHttpTest {
    private static final String SCOPE = "patient-gates:coordinate";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @BeforeAll
    static void requireDatabase() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: guard HTTP tests skipped");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
    }

    @Test
    void deactivationGuardIsAcquiredRecoveredByOperationAndFinalizedWithTheCurrentToken() throws Exception {
        String patient = UUID.randomUUID().toString();
        String operation = UUID.randomUUID().toString();
        JsonNode first = body(acquire(patient, "deactivation-guards", operation, "patients-service")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("OPEN"))
                .andExpect(jsonPath("$.operationId").value(operation))
                .andExpect(jsonPath("$.token").isString()));
        JsonNode again = body(acquire(patient, "deactivation-guards", operation, "patients-service")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(first.get("id").asText()))
                .andExpect(jsonPath("$.token").value(not(first.get("token").asText()))));
        String id = first.get("id").asText();

        call(get("/internal/v1/patient-guards?operationId=" + operation), "patients-service")
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.token").doesNotExist());
        call(get("/internal/v1/patient-guards/" + id), "clinical-service").andExpect(status().isNotFound());
        resolve(id, first.get("token").asText(), "FINALIZED").andExpect(status().isConflict());
        resolve(id, again.get("token").asText(), "COMMITTED").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("outcome"));
        resolve(id, again.get("token").asText(), "FINALIZED").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINALIZED"));
        call(get("/internal/v1/patient-guards/" + id), "patients-service").andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FINALIZED"));

        acquire(patient, "activity-guards", UUID.randomUUID().toString(), "clinical-service")
                .andExpect(status().isConflict());
    }

    @Test
    void requestsNeedTheScopeAValidBodyAndAKnownGuard() throws Exception {
        String patient = UUID.randomUUID().toString();
        mvc.perform(post("/internal/v1/patients/" + patient + "/activity-guards").contentType("application/json")
                        .content("{\"operationId\":\"" + UUID.randomUUID() + "\"}")
                        .header("Authorization", TestIdentity.service("clinical-service", "assignments:read")))
                .andExpect(status().isForbidden());
        call(post("/internal/v1/patients/" + patient + "/activity-guards").contentType("application/json")
                .content("{}"), "clinical-service")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details[0].field").value("operationId"));
        call(get("/internal/v1/patient-guards"), "clinical-service").andExpect(status().isBadRequest());
        call(get("/internal/v1/patient-guards?operationId=" + UUID.randomUUID()), "clinical-service")
                .andExpect(status().isNotFound());
        resolve(UUID.randomUUID().toString(), "token", "ABORTED").andExpect(status().isNotFound());
    }

    private ResultActions acquire(String patient, String kind, String operation, String caller) throws Exception {
        return call(post("/internal/v1/patients/" + patient + "/" + kind).contentType("application/json")
                .content("{\"operationId\":\"" + operation + "\"}"), caller);
    }

    private ResultActions resolve(String id, String token, String outcome) throws Exception {
        return call(post("/internal/v1/patient-guards/" + id + "/resolutions").contentType("application/json")
                .content("{\"token\":\"" + token + "\",\"outcome\":\"" + outcome + "\"}"), "patients-service");
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                               String caller) throws Exception {
        return mvc.perform(request.header("Authorization", TestIdentity.service(caller, SCOPE)));
    }

    private static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
