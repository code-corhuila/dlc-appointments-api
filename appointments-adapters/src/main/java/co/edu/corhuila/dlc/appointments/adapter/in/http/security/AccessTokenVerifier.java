package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import com.nimbusds.jwt.JWTClaimsSet;

/**
 * Verifies a staff access token and its current session.
 * Throws BadJwtException for a rejected token or inactive session and
 * AuthSessionValidationUnavailableException when the session authority cannot answer.
 */
@FunctionalInterface
public interface AccessTokenVerifier {
    JWTClaimsSet verify(String accessToken);
}
