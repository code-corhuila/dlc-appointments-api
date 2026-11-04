package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import co.edu.corhuila.dlc.appointments.adapter.in.http.ErrorEnvelopeWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every /internal/ route needs a verified service JWT; anything else answers 401 with a Bearer challenge.
 * The scope of each operation is checked by the operation itself (403 when absent).
 */
public final class ServiceAuthenticationFilter extends OncePerRequestFilter {
    private static final String INTERNAL_PREFIX = "/internal/";
    private static final String BEARER = "Bearer ";
    private final ServiceJwtVerifier verifier;

    public ServiceAuthenticationFilter(ServiceJwtVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().substring(request.getContextPath().length()).startsWith(INTERNAL_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        Optional<AuthenticatedService> service = header != null
                && header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? verifier.verify(header.substring(BEARER.length()).trim()) : Optional.empty();
        if (service.isEmpty()) {
            response.setHeader("WWW-Authenticate", "Bearer");
            ErrorEnvelopeWriter.write(request, response, 401, "UNAUTHORIZED", "Service authentication is required.");
            return;
        }
        request.setAttribute(AuthenticatedService.ATTRIBUTE, service.get());
        chain.doFilter(request, response);
    }
}
