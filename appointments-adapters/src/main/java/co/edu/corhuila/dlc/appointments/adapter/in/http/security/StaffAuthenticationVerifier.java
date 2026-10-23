package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import co.edu.corhuila.dlc.appointments.adapter.out.auth.AuthSessionValidationClient;
import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Objects;
import org.springframework.security.oauth2.jwt.BadJwtException;

/**
 * Isolated JWT-then-session coordination, not HTTP authentication or resource authorization.
 * Trusted keys, current service credentials and safe Auth transport remain caller responsibilities.
 * No token/claims cache, Spring bean, sid conversion or role evaluation is introduced.
 */
public final class StaffAuthenticationVerifier {
    private final StaffJwtSignatureVerifier jwt;
    private final AuthSessionValidationClient sessions;

    public StaffAuthenticationVerifier(StaffJwtSignatureVerifier jwt, AuthSessionValidationClient sessions) {
        this.jwt = Objects.requireNonNull(jwt, "jwt");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /** Invalid JWTs fail before Auth. Dependency failures propagate without becoming inactivity. */
    public JWTClaimsSet verify(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new BadJwtException("A staff access token is required.");
        }
        JWTClaimsSet claims = jwt.verifySignatureAndClaims(accessToken);
        if (!sessions.isActive(accessToken)) {
            throw new InactiveSessionException();
        }
        return claims;
    }

    /** Auth confirmed inactivity; distinct from local JWT rejection and dependency failure. */
    public static final class InactiveSessionException extends BadJwtException {
        public InactiveSessionException() { super("The staff session is inactive."); }
    }
}