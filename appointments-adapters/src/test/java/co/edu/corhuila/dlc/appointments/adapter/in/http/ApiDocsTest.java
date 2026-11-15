package co.edu.corhuila.dlc.appointments.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Swagger UI over the dlc-docs contracts, read in place: contract-first, nothing generated from code. */
class ApiDocsTest {
    @TempDir
    Path contracts;

    private MockMvc mvc;

    @BeforeEach
    void contracts() throws IOException {
        Files.writeString(contracts.resolve("appointments-service.yaml"), "openapi: 3.0.3\ninfo: {title: public}\n");
        Files.writeString(contracts.resolve("internal-coordination.yaml"), "openapi: 3.0.3\ninfo: {title: internal}\n");
        Files.writeString(contracts.resolve("_shared.yaml"), "components: {}\n");
        Files.writeString(contracts.resolve("billing-service.yaml"), "openapi: 3.0.3\n");
        mvc = MockMvcBuilders.routerFunctions(new ApiDocs(contracts).routes()).build();
    }

    @Test
    void thePageLoadsSwaggerUiWithBothAppointmentsContracts() throws Exception {
        mvc.perform(get("/docs"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/html")))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("/docs/assets/swagger-ui-bundle.js")))
                .andExpect(content().string(containsString("/docs/assets/swagger-ui.css")))
                .andExpect(content().string(containsString("/docs/contracts/appointments-service.yaml")))
                .andExpect(content().string(containsString("/docs/contracts/internal-coordination.yaml")))
                .andExpect(content().string(not(containsString("https://"))));
    }

    @Test
    void theContractsAreServedAsTheyAreInDlcDocs() throws Exception {
        mvc.perform(get("/docs/contracts/appointments-service.yaml"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("application/yaml")))
                .andExpect(content().string("openapi: 3.0.3\ninfo: {title: public}\n"));
        mvc.perform(get("/docs/contracts/internal-coordination.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("internal")));
        mvc.perform(get("/docs/contracts/_shared.yaml")).andExpect(status().isOk());
    }

    @Test
    void onlyTheAppointmentsContractsAreServed() throws Exception {
        mvc.perform(get("/docs/contracts/billing-service.yaml")).andExpect(status().isNotFound());
        mvc.perform(get("/docs/contracts/..%2F..%2Fetc%2Fpasswd")).andExpect(status().isNotFound());
        mvc.perform(get("/docs/contracts/")).andExpect(status().isNotFound());
    }

    @Test
    void aContractMissingFromTheCheckoutIsNotFound() throws Exception {
        Files.delete(contracts.resolve("_shared.yaml"));
        mvc.perform(get("/docs/contracts/_shared.yaml")).andExpect(status().isNotFound());
    }

    @Test
    void theContractsFolderMustExist() {
        assertThrows(IllegalArgumentException.class, () -> new ApiDocs(contracts.resolve("missing")));
        assertThrows(NullPointerException.class, () -> new ApiDocs(null));
    }
}
