package co.edu.corhuila.dlc.appointments.app;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.atlassian.oai.validator.mockmvc.OpenApiMatchers;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** The contract matcher applied to every HTTP test rejects responses outside the dlc-docs OpenAPI contracts. */
class ContractValidationTest {

    @BeforeAll
    static void requireContracts() {
        assumeTrue(ContractValidation.ENABLED, "DLC_DOCS_DIR not set: contract checks skipped");
    }

    @Test
    void aResponseOutsideTheContractFails() {
        String path = "/api/v1/appointments/" + UUID.randomUUID();
        assertThrows(OpenApiMatchers.OpenApiValidationException.class,
                () -> ContractValidation.MATCHER.match(result(path, 200, "{\"id\":1}")));
        assertThrows(OpenApiMatchers.OpenApiValidationException.class,
                () -> ContractValidation.MATCHER.match(result(path, 418, "{}")));
        assertThrows(OpenApiMatchers.OpenApiValidationException.class, () -> ContractValidation.MATCHER.match(
                result("/internal/v1/patient-guards/" + UUID.randomUUID(), 200, "{\"state\":\"LOST\"}")));
    }

    @Test
    void aContractErrorEnvelopeAndRoutesOutsideTheContractsPass() {
        String error = "{\"error\":\"NOT_FOUND\",\"message\":\"Not found.\",\"details\":[],\"traceId\":\"t-1\"}";
        assertDoesNotThrow(() -> ContractValidation.MATCHER.match(
                result("/api/v1/appointments/" + UUID.randomUUID(), 404, error)));
        assertDoesNotThrow(() -> ContractValidation.MATCHER.match(result("/health", 200, "{}")));
    }

    private static final MockMvc MVC = MockMvcBuilders.standaloneSetup(new Echo()).build();

    /** Answers the status and JSON body given in headers, for any path. */
    @RestController
    static class Echo {
        @GetMapping("/**")
        ResponseEntity<String> echo(@RequestHeader("X-Status") int status, @RequestHeader("X-Body") String body) {
            return ResponseEntity.status(status).header("Content-Type", "application/json").body(body);
        }
    }

    private static org.springframework.test.web.servlet.MvcResult result(String path, int status, String body)
            throws Exception {
        ResultActions actions = MVC.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer x")
                .header("X-Status", status).header("X-Body", body));
        return actions.andReturn();
    }
}
