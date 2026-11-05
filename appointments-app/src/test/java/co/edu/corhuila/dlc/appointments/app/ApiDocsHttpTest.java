package co.edu.corhuila.dlc.appointments.app;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** APPOINTMENTS_API_DOCS_DIR enables Swagger UI at /docs over the dlc-docs contracts; the API keeps its tokens. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsHttpTest {
    private static final Path CONTRACTS = contracts();

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("appointments.api-docs.contracts-dir", CONTRACTS::toString);
    }

    @Test
    void theUiItsAssetsAndTheContractsAreServedWithoutAToken() throws Exception {
        mvc.perform(get("/docs")).andExpect(status().isOk())
                .andExpect(content().string(containsString("SwaggerUIBundle")));
        mvc.perform(get("/docs/assets/swagger-ui-bundle.js")).andExpect(status().isOk());
        mvc.perform(get("/docs/assets/swagger-ui.css")).andExpect(status().isOk());
        mvc.perform(get("/docs/contracts/appointments-service.yaml")).andExpect(status().isOk())
                .andExpect(content().string(containsString("openapi")));
    }

    @Test
    void theApiStillRequiresItsTokens() throws Exception {
        mvc.perform(get("/api/v1/appointments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/patient-guards/x")).andExpect(status().isUnauthorized());
    }

    private static Path contracts() {
        try {
            Path dir = Files.createTempDirectory("dlc-contracts");
            Files.writeString(dir.resolve("appointments-service.yaml"), "openapi: 3.0.3\n");
            Files.writeString(dir.resolve("internal-coordination.yaml"), "openapi: 3.0.3\n");
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
