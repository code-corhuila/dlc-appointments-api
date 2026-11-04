package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import com.nimbusds.jwt.JWTClaimsSet;
import java.text.ParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Staff identity verified for the current request; roles still need owner-side authorization. */
public record AuthenticatedStaff(UUID userId, UUID sessionId, Set<String> roles, Set<String> permissions) {
    public static final String ATTRIBUTE = AuthenticatedStaff.class.getName();

    public AuthenticatedStaff {
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }

    /** Empty when the verified claims lack a staff UUID subject or a session UUID. */
    static Optional<AuthenticatedStaff> fromClaims(JWTClaimsSet claims) {
        try {
            UUID userId = UUID.fromString(claims.getSubject());
            UUID sessionId = UUID.fromString(claims.getStringClaim("sid"));
            return Optional.of(new AuthenticatedStaff(userId, sessionId,
                    Set.copyOf(listOrEmpty(claims.getStringListClaim("roles"))),
                    Set.copyOf(listOrEmpty(claims.getStringListClaim("permissions")))));
        } catch (ParseException | IllegalArgumentException | NullPointerException invalid) {
            return Optional.empty();
        }
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    private static List<String> listOrEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
