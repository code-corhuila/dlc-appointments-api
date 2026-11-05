package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.math.BigDecimal;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.BadJwtException;

/**
 * Signature, claim structure and strict temporal checks; NOT a full authentication decision.
 * No leeway is applied: iat <= now < exp and optional nbf <= now. This conservative
 * implementation choice is not a contractual clock-skew policy; approval remains pending.
 * Current session checks and authorization are still required before authenticating requests.
 *
 * Construction is isolated from production JWKS retrieval/rotation. The caller must supply
 * a public key from Auth's verified JWKS; token-supplied key URLs are never consulted.
 * No Spring bean or SecurityFilterChain is registered here.
 */
public final class StaffJwtSignatureVerifier {
    private final RSAPublicKey publicKey;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public StaffJwtSignatureVerifier(RSAPublicKey publicKey, String issuer, String audience) {
        this(publicKey, issuer, audience, Clock.systemUTC());
    }

    public StaffJwtSignatureVerifier(RSAPublicKey publicKey, String issuer, String audience, Clock clock) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        this.issuer = configured(issuer);
        this.audience = configured(audience);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Does not establish active session, current roles or permissions. */
    public JWTClaimsSet verifySignatureAndClaims(String encoded) {
        try {
            var token = SignedJWT.parse(encoded);
            if (!JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm())
                    || !token.verify(new RSASSAVerifier(publicKey))) {
                throw invalid();
            }
            var claims = token.getPayload().toJSONObject();
            if (claims == null || !validClaims(claims) || !validTimes(claims)) {
                throw invalid();
            }
            return token.getJWTClaimsSet();
        } catch (ParseException | JOSEException | IllegalArgumentException failure) {
            // Do not retain parser messages or causes that may contain credential material.
            throw invalid();
        }
    }

    private boolean validClaims(Map<String, Object> claims) {
        return issuer.equals(claims.get("iss")) && intendedAudience(claims.get("aud"))
                && staffUuid(claims.get("sub")) && text(claims.get("sid")) && text(claims.get("jti"))
                && numericDate(claims.get("iat")) && numericDate(claims.get("exp"))
                && (!claims.containsKey("nbf") || numericDate(claims.get("nbf")))
                && stringArray(claims.get("roles")) && stringArray(claims.get("permissions"));
    }

    private boolean intendedAudience(Object value) {
        return audience.equals(value) || stringArray(value) && ((List<?>) value).contains(audience);
    }

    private boolean validTimes(Map<String, Object> claims) {
        var instant = clock.instant();
        var now = BigDecimal.valueOf(instant.getEpochSecond()).add(BigDecimal.valueOf(instant.getNano(), 9));
        // Compare original NumericDates before Nimbus converts them to lower-precision Dates.
        var issued = seconds(claims.get("iat"));
        var expires = seconds(claims.get("exp"));
        return issued.compareTo(now) <= 0 && now.compareTo(expires) < 0
                && issued.compareTo(expires) < 0
                && (!claims.containsKey("nbf") || seconds(claims.get("nbf")).compareTo(now) <= 0);
    }

    private static BigDecimal seconds(Object numericDate) {
        return new BigDecimal(numericDate.toString());
    }

    private static boolean staffUuid(Object value) {
        if (!(value instanceof String subject)) return false;
        try {
            return UUID.fromString(subject).toString().equalsIgnoreCase(subject);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean stringArray(Object value) {
        return value instanceof List<?> list && list.stream().allMatch(String.class::isInstance);
    }

    private static boolean text(Object value) {
        return value instanceof String text && !text.isBlank();
    }

    private static boolean numericDate(Object value) {
        return value instanceof Number number && Double.isFinite(number.doubleValue());
    }

    private static String configured(String value) {
        if (!text(value)) throw new IllegalArgumentException("Issuer and audience must be configured.");
        return value;
    }

    private static BadJwtException invalid() {
        return new BadJwtException("Invalid staff JWT signature or claims.");
    }
}
