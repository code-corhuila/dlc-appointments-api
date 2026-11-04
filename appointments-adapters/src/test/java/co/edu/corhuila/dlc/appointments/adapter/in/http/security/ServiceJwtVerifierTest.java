package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** Service JWTs for /internal/v1: RS256, own audience, strict times and explicit scopes (authentication.md). */
class ServiceJwtVerifierTest {
    private static final Instant NOW = Instant.parse("2031-05-01T12:00:00Z");
    private static final RSAKey KEY = key();
    private static final RSAKey OTHER = key();
    private final ServiceJwtVerifier verifier = new ServiceJwtVerifier(publicKey(KEY), "https://services.test",
            "dlc-appointments", Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void validTokenYieldsTheCallingServiceAndItsScopesFromScopeOrScp() {
        Optional<AuthenticatedService> spaced = verifier.verify(token(KEY, c -> c.claim("scope",
                "assignments:read appointments:encounter-status:read")));
        assertEquals("dlc-clinical-api", spaced.orElseThrow().subject());
        assertEquals(Set.of("assignments:read", "appointments:encounter-status:read"), spaced.get().scopes());
        assertTrue(spaced.get().hasScope("assignments:read"));

        var listed = verifier.verify(token(KEY, c -> c.claim("scope", null)
                .claim("scp", List.of("notifications:outcomes:write"))));
        assertEquals(Set.of("notifications:outcomes:write"), listed.orElseThrow().scopes());
    }

    @Test
    void anyOtherKeyAlgorithmIssuerAudienceOrTimeIsRejected() {
        assertTrue(verifier.verify(token(OTHER, c -> c)).isEmpty());
        assertTrue(verifier.verify(token(KEY, c -> c.issuer("https://auth.test"))).isEmpty());
        assertTrue(verifier.verify(token(KEY, c -> c.audience("dlc-api-test"))).isEmpty());
        assertTrue(verifier.verify(token(KEY, c -> c.expirationTime(Date.from(NOW)))).isEmpty());
        assertTrue(verifier.verify(token(KEY, c -> c.issueTime(null))).isEmpty());
        assertTrue(verifier.verify(token(KEY, c -> c.subject(" "))).isEmpty());
        assertTrue(verifier.verify(hs256()).isEmpty());
        assertTrue(verifier.verify("not-a-jwt").isEmpty());
    }

    private static String token(RSAKey key, UnaryOperator<JWTClaimsSet.Builder> change) {
        var claims = new JWTClaimsSet.Builder().issuer("https://services.test").audience("dlc-appointments")
                .subject("dlc-clinical-api").issueTime(Date.from(NOW.minusSeconds(30)))
                .expirationTime(Date.from(NOW.plusSeconds(270))).claim("scope", "assignments:read");
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), change.apply(claims).build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String hs256() {
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                    .issuer("https://services.test").audience("dlc-appointments").subject("x")
                    .issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(60))).build());
            jwt.sign(new MACSigner("a-shared-secret-of-at-least-32-bytes!!"));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static java.security.interfaces.RSAPublicKey publicKey(RSAKey key) {
        try {
            return key.toRSAPublicKey();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
