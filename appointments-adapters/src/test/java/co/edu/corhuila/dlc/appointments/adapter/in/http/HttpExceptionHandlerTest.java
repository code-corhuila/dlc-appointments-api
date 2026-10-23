package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.domain.model.InvalidAppointmentException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class HttpExceptionHandlerTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new HttpExceptionHandler()).build();

    @Test
    void rendersKnownDomainErrorsAsJson() throws Exception {
        mvc.perform(get("/probe/invalid"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("reason"));
        int[] statuses = {409, 409, 409, 503, 403, 409};
        int index = 0;
        for (var reason : SchedulingException.Reason.values()) {
            mvc.perform(get("/probe/scheduling/{reason}", reason.name()))
                    .andExpect(status().is(statuses[index++]))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.message").isString());
        }
    }

    @Test
    void malformedJsonAndConversionErrorsAreSafeBadRequests() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{secret-input"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-input"))));
        mvc.perform(get("/probe/uuid").param("id", "secret-input"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        mvc.perform(get("/probe/uuid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void unexpectedFailureIsSafeAndClientTraceHeadersAreIgnored() throws Exception {
        mvc.perform(get("/probe/unexpected").header("X-Correlation-Id", "untrusted").header("traceId", "untrusted"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.traceId").doesNotExist()).andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void preservesTraceOnlyFromExplicitServerSideInstrumentationAttribute() throws Exception {
        mvc.perform(get("/probe/unexpected").requestAttr(HttpExceptionHandler.TRUSTED_TRACE_ID_ATTRIBUTE, "verified-trace"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.traceId").value("verified-trace"));
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{")
                .requestAttr(HttpExceptionHandler.TRUSTED_TRACE_ID_ATTRIBUTE, "verified-trace"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.traceId").value("verified-trace"));
    }

    @Test
    void recognizedMvcStatusesAreNotConvertedToInternalErrors() throws Exception {
        mvc.perform(post("/probe/invalid")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("text"))
                .andExpect(status().isUnsupportedMediaType());
    }

    // Test-only routes; this controller is never registered in production.
    @RestController
    static class ProbeController {
        @GetMapping("/probe/invalid")
        void invalid() { throw new InvalidAppointmentException("reason", "Reason is required."); }
        @GetMapping("/probe/scheduling/{reason}")
        void scheduling(@PathVariable("reason") String reason) {
            throw new SchedulingException(SchedulingException.Reason.valueOf(reason));
        }
        @GetMapping("/probe/unexpected")
        void unexpected() { throw new IllegalStateException("secret-sql"); }
        @PostMapping(value = "/probe/body", consumes = MediaType.APPLICATION_JSON_VALUE)
        void body(@RequestBody Map<String, Object> body) { }
        @GetMapping("/probe/uuid")
        void uuid(@RequestParam("id") UUID id) { }
    }
}
