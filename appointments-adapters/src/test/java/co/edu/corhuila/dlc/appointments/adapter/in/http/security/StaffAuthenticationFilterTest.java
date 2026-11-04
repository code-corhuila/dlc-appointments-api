package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.edu.corhuila.dlc.appointments.adapter.in.http.HttpExceptionHandler;
import co.edu.corhuila.dlc.appointments.adapter.out.auth.AuthSessionValidationUnavailableException;
import com.nimbusds.jwt.JWTClaimsSet;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class StaffAuthenticationFilterTest {
    private static final String STAFF = "233665d0-c575-4ac1-8891-e96c0811b64a";
    private static final String SESSION = "0b8f5d1e-7f0c-4a53-9b7e-3c1c9a6a2f10";

    @Test
    void missingBearerTokenAnswersUnauthorizedEnvelopeWithChallenge() throws Exception {
        mvc(token -> claims()).perform(get("/api/v1/probe")
                        .requestAttr(HttpExceptionHandler.TRUSTED_TRACE_ID_ATTRIBUTE, "trace-401"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").value("trace-401"));
    }

    @Test
    void nonBearerSchemeIsUnauthorized() throws Exception {
        mvc(token -> claims()).perform(get("/api/v1/probe").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void rejectedTokenOrInactiveSessionIsUnauthorized() throws Exception {
        mvc(token -> { throw new BadJwtException("detail never exposed"); })
                .perform(get("/api/v1/probe").header("Authorization", "Bearer forged"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required."));
        mvc(token -> { throw new StaffAuthenticationVerifier.InactiveSessionException(); })
                .perform(get("/api/v1/probe").header("Authorization", "Bearer revoked"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unavailableSessionAuthorityFailsClosedWithServiceUnavailable() throws Exception {
        mvc(token -> { throw new AuthSessionValidationUnavailableException(); })
                .perform(get("/api/v1/probe").header("Authorization", "Bearer valid"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void claimsWithoutStaffUuidOrSessionAreUnauthorized() throws Exception {
        mvc(token -> new JWTClaimsSet.Builder().subject("not-a-uuid").claim("sid", SESSION).build())
                .perform(get("/api/v1/probe").header("Authorization", "Bearer valid"))
                .andExpect(status().isUnauthorized());
        mvc(token -> new JWTClaimsSet.Builder().subject(STAFF).build())
                .perform(get("/api/v1/probe").header("Authorization", "Bearer valid"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void verifiedStaffReachesTheControllerAsRequestPrincipal() throws Exception {
        mvc(token -> claims()).perform(get("/api/v1/probe").header("Authorization", "Bearer valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(STAFF))
                .andExpect(jsonPath("$.sessionId").value(SESSION))
                .andExpect(jsonPath("$.roles[0]").value("SECRETARY_ASSISTANT"));
    }

    @Test
    void routesOutsideThePublicApiAreNotAuthenticatedHere() throws Exception {
        mvc(token -> { throw new BadJwtException("never called"); }).perform(get("/health"))
                .andExpect(status().isOk());
    }

    private static JWTClaimsSet claims() {
        return new JWTClaimsSet.Builder().subject(STAFF).claim("sid", SESSION)
                .claim("roles", List.of("SECRETARY_ASSISTANT")).claim("permissions", List.of()).build();
    }

    private static MockMvc mvc(AccessTokenVerifier verifier) {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .addFilters(new StaffAuthenticationFilter(verifier)).build();
    }

    // Test-only routes; this controller is never registered in production.
    @RestController
    static class ProbeController {
        @GetMapping("/api/v1/probe")
        AuthenticatedStaff probe(HttpServletRequest request) {
            return (AuthenticatedStaff) request.getAttribute(AuthenticatedStaff.ATTRIBUTE);
        }

        @GetMapping("/health")
        String health() { return "UP"; }
    }
}
