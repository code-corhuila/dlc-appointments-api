package co.edu.corhuila.dlc.appointments.app;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** /internal/v1 accepts only service JWTs; staff tokens never substitute for a service identity. */
@SpringBootTest
@AutoConfigureMockMvc
class InternalServiceAuthHttpTest {
    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
    }

    @Test
    void internalRoutesWithoutAServiceTokenAreUnauthorized() throws Exception {
        mvc.perform(get("/internal/v1/does-not-exist"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
        mvc.perform(get("/internal/v1/does-not-exist")
                        .header("Authorization", TestIdentity.bearer(TestIdentity.SECRETARY, "ADMINISTRATOR")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anAuthenticatedServiceReachesRoutingAndCannotUsePublicRoutes() throws Exception {
        mvc.perform(get("/internal/v1/does-not-exist")
                        .header("Authorization", TestIdentity.service("dlc-clinical-api", "assignments:read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/appointments")
                        .header("Authorization", TestIdentity.service("dlc-clinical-api", "assignments:read")))
                .andExpect(status().isUnauthorized());
    }
}
