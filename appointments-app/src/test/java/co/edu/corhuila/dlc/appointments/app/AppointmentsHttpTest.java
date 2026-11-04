package co.edu.corhuila.dlc.appointments.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

/** Annex C "Sobre de error y correlación" checks through the composed application. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AppointmentsHttpTest {
    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Autowired
    private MockMvc mvc;

    @Test
    void healthAnswersWithoutTokenAndGeneratesCorrelationId() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void receivedCorrelationIdIsReturned() throws Exception {
        mvc.perform(get("/health").header("X-Correlation-Id", "e2e-saga-1"))
                .andExpect(header().string("X-Correlation-Id", "e2e-saga-1"));
    }

    @Test
    void unsafeCorrelationIdIsReplacedByGeneratedOne() throws Exception {
        mvc.perform(get("/health").header("X-Correlation-Id", "bad value\r\ninjected"))
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
        mvc.perform(get("/health").header("X-Correlation-Id", "a".repeat(129)))
                .andExpect(header().string("X-Correlation-Id", matchesPattern(UUID_PATTERN)));
    }

    @Test
    void unknownRouteAnswersNotFoundEnvelopeWithTraceId() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist").header("X-Correlation-Id", "trace-404"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "trace-404"))
                .andExpect(jsonPath("$.error").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.traceId").value("trace-404"));
    }

    @Test
    void unsupportedMethodAnswersEnvelope() throws Exception {
        mvc.perform(post("/health").header("X-Correlation-Id", "trace-405"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.traceId").value("trace-405"));
    }

    @Test
    void everyLogLineIsJsonWithTheCorrelationId(CapturedOutput output) throws Exception {
        mvc.perform(get("/health").header("X-Correlation-Id", "trace-log-1"));

        assertThat(output.getOut().lines().filter(line -> line.contains("trace-log-1")))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line)
                        .startsWith("{")
                        .contains("\"correlationId\":\"trace-log-1\""));
    }
}
