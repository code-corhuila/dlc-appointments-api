package co.edu.corhuila.dlc.appointments.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Without APPOINTMENTS_API_DOCS_DIR, Swagger UI and the contracts are not exposed. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiDocsDisabledHttpTest {
    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
    }

    @Test
    void nothingIsExposed() throws Exception {
        mvc.perform(get("/docs")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(get("/docs/assets/swagger-ui-bundle.js")).andExpect(status().isNotFound());
        mvc.perform(get("/docs/contracts/appointments-service.yaml")).andExpect(status().isNotFound());
    }
}
