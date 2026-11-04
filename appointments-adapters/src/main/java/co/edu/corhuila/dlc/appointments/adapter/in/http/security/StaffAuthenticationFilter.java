package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import co.edu.corhuila.dlc.appointments.adapter.in.http.ErrorEnvelopeWriter;
import co.edu.corhuila.dlc.appointments.adapter.out.auth.AuthSessionValidationUnavailableException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Norma 5.3.7: every public /api route needs a staff bearer token verified by this service (RS256,
 * claims and current session). Rejections answer 401 with a Bearer challenge; an unavailable
 * session authority answers 503 (fail closed). Authorization by role stays in each operation.
 */
public final class StaffAuthenticationFilter extends OncePerRequestFilter {
    private static final String PROTECTED_PREFIX = "/api/";
    private static final String BEARER = "Bearer ";
    private final AccessTokenVerifier verifier;

    public StaffAuthenticationFilter(AccessTokenVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith(PROTECTED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<AuthenticatedStaff> staff;
        try {
            staff = bearerToken(request).map(verifier::verify).flatMap(AuthenticatedStaff::fromClaims);
        } catch (BadJwtException rejected) {
            staff = Optional.empty();
        } catch (AuthSessionValidationUnavailableException unavailable) {
            ErrorEnvelopeWriter.write(request, response, 503, "SERVICE_UNAVAILABLE",
                    "The operation is temporarily unavailable.");
            return;
        }
        if (staff.isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer");
            ErrorEnvelopeWriter.write(request, response, 401, "UNAUTHORIZED", "Authentication is required.");
            return;
        }
        request.setAttribute(AuthenticatedStaff.ATTRIBUTE, staff.get());
        chain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) return Optional.empty();
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
