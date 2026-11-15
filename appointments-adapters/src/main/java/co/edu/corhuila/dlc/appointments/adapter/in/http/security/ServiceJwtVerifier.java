package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Short-lived service JWTs for /internal/v1 (authentication.md): RS256 with the configured service key, this
 * service's issuer and audience, iat <= now < exp and a non-blank subject. Scopes come from a space-separated
 * "scope" or a "scp" array. A staff token never matches, because its issuer, audience and key differ.
 */
public final class ServiceJwtVerifier {
    private final RSAPublicKey publicKey;
    private final String issuer;
    private final String audience;
    private final Clock clock;

    public ServiceJwtVerifier(RSAPublicKey publicKey, String issuer, String audience, Clock clock) {
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        this.issuer = configured(issuer, "issuer");
        this.audience = configured(audience, "audience");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<AuthenticatedService> verify(String encoded) {
        try {
            SignedJWT token = SignedJWT.parse(encoded);
            if (!JWSAlgorithm.RS256.equals(token.getHeader().getAlgorithm())
                    || !token.verify(new RSASSAVerifier(publicKey))) {
                return Optional.empty();
            }
            JWTClaimsSet claims = token.getJWTClaimsSet();
            Instant now = clock.instant();
            Date issued = claims.getIssueTime();
            Date expires = claims.getExpirationTime();
            String subject = claims.getSubject();
            if (!issuer.equals(claims.getIssuer()) || claims.getAudience() == null
                    || !claims.getAudience().contains(audience) || issued == null || expires == null
                    || issued.toInstant().isAfter(now) || !now.isBefore(expires.toInstant())
                    || subject == null || subject.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AuthenticatedService(subject, scopes(claims)));
        } catch (Exception rejected) {
            // Parser and crypto messages may echo credential material; nothing is retained.
            return Optional.empty();
        }
    }

    private static Set<String> scopes(JWTClaimsSet claims) throws java.text.ParseException {
        Set<String> scopes = new HashSet<>();
        String spaced = claims.getStringClaim("scope");
        if (spaced != null) scopes.addAll(Arrays.asList(spaced.trim().split("\\s+")));
        List<String> listed = claims.getStringListClaim("scp");
        if (listed != null) scopes.addAll(listed);
        scopes.remove("");
        return scopes;
    }

    private static String configured(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException("Service JWT " + name + " is required.");
        return value;
    }
}
