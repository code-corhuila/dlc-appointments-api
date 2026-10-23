package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.security.oauth2.jwt.BadJwtException;
import static org.junit.jupiter.api.Assertions.*;

class StaffJwtSignatureVerifierTest {
    private static RSAKey key;
    private static RSAKey otherKey;
    private static StaffJwtSignatureVerifier verifier;
    private static final String SUBJECT = "233665d0-c575-4ac1-8891-e96c0811b64a";
    private static final long NOW = 2000000000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    @BeforeAll
    static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("synthetic-test-key").generate();
        otherKey = new RSAKeyGenerator(2048).generate();
        verifier = new StaffJwtSignatureVerifier(key.toRSAPublicKey(), "test-issuer", "test-audience", CLOCK);
    }

    @Test
    void verifiesRealRs256SignatureAndPreservesClaims() throws Exception {
        var result = verifier.verifySignatureAndClaims(signed(claims(), key, JWSAlgorithm.RS256));
        assertEquals(SUBJECT, result.getSubject());
        assertEquals("session-opaque", result.getStringClaim("sid"));
        assertEquals(List.of("ADMINISTRATOR"), result.getStringListClaim("roles"));
        assertEquals(List.of(), result.getStringListClaim("permissions"));
    }

    @Test
    void rejectsWrongSigningKey() throws Exception {
        rejects(signed(claims(), otherKey, JWSAlgorithm.RS256));
    }

    @Test
    void rejectsUnsignedToken() {
        rejects(new PlainJWT(new JWTClaimsSet.Builder().subject(SUBJECT).build()).serialize());
    }

    @Test
    void rejectsRsaAlgorithmSubstitution() throws Exception {
        rejects(signed(claims(), key, JWSAlgorithm.RS512));
    }

    @Test
    void rejectsHmacInsteadOfRsa() throws Exception {
        var token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), JWTClaimsSet.parse(claims()));
        token.sign(new MACSigner(new byte[32]));
        rejects(token.serialize());
    }

    @Test
    void rejectsIncorrectIssuerAndAudience() throws Exception {
        for (var name : List.of("iss", "aud")) {
            var claims = claims();
            claims.put(name, "wrong");
            rejects(signed(claims, key, JWSAlgorithm.RS256));
        }
    }

    static Stream<String> requiredClaims() {
        return Stream.of("sub", "iss", "aud", "iat", "exp", "jti", "sid", "roles", "permissions");
    }

    @ParameterizedTest
    @MethodSource("requiredClaims")
    void rejectsMissingRequiredClaim(String name) throws Exception {
        var claims = claims();
        claims.remove(name);
        rejects(signed(claims, key, JWSAlgorithm.RS256));
    }

    @Test
    void rejectsMalformedSubjectAndSessionAndTokenIdentifiers() throws Exception {
        for (var entry : Map.<String, Object>of("sub", "not-a-uuid", "sid", 123, "jti", 123).entrySet()) {
            var claims = claims();
            claims.put(entry.getKey(), entry.getValue());
            rejects(signed(claims, key, JWSAlgorithm.RS256));
        }
        for (var name : List.of("sub", "sid", "jti")) {
            var claims = claims();
            claims.put(name, "");
            rejects(signed(claims, key, JWSAlgorithm.RS256));
        }
    }

    @Test
    void rejectsNonArrayOrNonStringRolesAndPermissions() throws Exception {
        for (var name : List.of("roles", "permissions")) {
            for (var value : List.of("ADMINISTRATOR", List.of(123))) {
                var claims = claims();
                claims.put(name, value);
                rejects(signed(claims, key, JWSAlgorithm.RS256));
            }
        }
    }

    @Test
    void rejectsNonNumericDates() throws Exception {
        for (var name : List.of("iat", "exp", "nbf")) {
            var claims = claims();
            claims.put(name, "not-a-numeric-date");
            rejects(signed(claims, key, JWSAlgorithm.RS256));
        }
    }

    @Test
    void neverFollowsKeyUrlsSuppliedByToken() throws Exception {
        var header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                .jwkURL(java.net.URI.create("https://untrusted.invalid/keys")).build();
        var token = new SignedJWT(header, JWTClaimsSet.parse(claims()));
        token.sign(new RSASSASigner(otherKey));
        rejects(token.serialize());
    }

    @Test
    void rejectsMalformedTokenWithoutEchoingIt() {
        var failure = assertThrows(BadJwtException.class,
                () -> verifier.verifySignatureAndClaims("sensitive-malformed-token"));
        assertEquals("Invalid staff JWT signature or claims.", failure.getMessage());
    }

    static Stream<Arguments> temporalCases() {
        return Stream.of(
                Arguments.of("valid", NOW - 1, NOW + 3599, null, true),
                Arguments.of("expired", NOW - 3601, NOW - 1, null, false),
                Arguments.of("expiration boundary", NOW - 3600, NOW, null, false),
                Arguments.of("future nbf", NOW, NOW + 3600, NOW + 1, false),
                Arguments.of("nbf boundary", NOW, NOW + 3600, NOW, true),
                Arguments.of("future iat", NOW + 1, NOW + 3601, null, false),
                Arguments.of("iat boundary", NOW, NOW + 3600, null, true),
                Arguments.of("inverted interval", NOW - 1, NOW - 2, null, false),
                Arguments.of("fractional future iat", new BigDecimal("2000000000.1"), NOW + 3600, null, false),
                Arguments.of("fractional future nbf", NOW, NOW + 3600, new BigDecimal("2000000000.1"), false),
                Arguments.of("fractional valid exp", NOW, new BigDecimal("2000000000.1"), null, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("temporalCases")
    void enforcesTemporalBoundaries(String scenario, Number iat, Number exp, Number nbf, boolean valid)
            throws Exception {
        var claims = claims();
        claims.put("iat", iat);
        claims.put("exp", exp);
        if (nbf != null) claims.put("nbf", nbf);
        var token = signed(claims, key, JWSAlgorithm.RS256);
        if (valid) assertNotNull(verifier.verifySignatureAndClaims(token));
        else rejects(token);
    }

    private static Map<String, Object> claims() {
        return new HashMap<>(Map.of("sub", SUBJECT, "iss", "test-issuer", "aud", List.of("test-audience"),
                "iat", 2000000000L, "exp", 2000003600L, "jti", "test-token-id", "sid", "session-opaque",
                "roles", List.of("ADMINISTRATOR"), "permissions", List.of()));
    }

    private static String signed(Map<String, Object> claims, RSAKey signingKey, JWSAlgorithm algorithm)
            throws Exception {
        // Raw payload permits malformed claims to reach the component under test.
        var token = new JWSObject(new JWSHeader(algorithm), new Payload(claims));
        token.sign(new RSASSASigner(signingKey));
        return token.serialize();
    }

    private void rejects(String token) {
        assertThrows(BadJwtException.class, () -> verifier.verifySignatureAndClaims(token));
    }
}
