package co.edu.corhuila.dlc.appointments.app;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Synthetic identity for HTTP tests: a test-only RSA pair and a fake Auth session endpoint. */
final class TestIdentity {
    static final String ISSUER = "https://auth.test.local";
    static final String AUDIENCE = "dlc-api-test";
    static final String SECRETARY = "a9900000-0000-4000-8000-0000000000c1";
    static final String DENTIST = "a9900000-0000-4000-8000-0000000000d1";
    private static final RSAKey KEY = generate();
    private static final RSAKey OTHER_KEY = generate();
    private static final HttpServer AUTH = fakeAuth();

    private TestIdentity() { }

    static void register(DynamicPropertyRegistry registry) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(publicKey());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----";
        registry.add("appointments.security.jwt-public-key", () -> pem.replace("\n", "\\n"));
        registry.add("appointments.security.jwt-issuer", () -> ISSUER);
        registry.add("appointments.security.jwt-audience", () -> AUDIENCE);
        registry.add("appointments.auth.internal-url",
                () -> "http://localhost:" + AUTH.getAddress().getPort());
        registry.add("appointments.auth.service-token", () -> "synthetic-service-token");
        // Unreachable on purpose: HTTP tests that need the database configure a real one.
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:1/unreachable");
    }

    static String bearer(String staffId, String... roles) {
        return "Bearer " + rs256(claims(staffId, Instant.now().plusSeconds(600), roles), KEY);
    }

    static String expired(String staffId) {
        return "Bearer " + rs256(claims(staffId, Instant.now().minusSeconds(120), "SECRETARY_ASSISTANT"), KEY);
    }

    static String signedWithOtherKey(String staffId) {
        return "Bearer " + rs256(claims(staffId, Instant.now().plusSeconds(600), "SECRETARY_ASSISTANT"), OTHER_KEY);
    }

    static String unsigned(String staffId) {
        return "Bearer " + new PlainJWT(claims(staffId, Instant.now().plusSeconds(600), "SECRETARY_ASSISTANT"))
                .serialize();
    }

    static String hs256(String staffId) {
        try {
            var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
                    claims(staffId, Instant.now().plusSeconds(600), "SECRETARY_ASSISTANT"));
            jwt.sign(new MACSigner(publicKey()));
            return "Bearer " + jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static JWTClaimsSet claims(String staffId, Instant expires, String... roles) {
        Instant issued = expires.minusSeconds(3600);
        if (issued.isAfter(Instant.now())) issued = Instant.now().minusSeconds(1);
        return new JWTClaimsSet.Builder().issuer(ISSUER).audience(AUDIENCE).subject(staffId)
                .jwtID(UUID.randomUUID().toString()).claim("sid", UUID.randomUUID().toString())
                .issueTime(Date.from(issued)).expirationTime(Date.from(expires))
                .claim("roles", List.of(roles)).claim("permissions", List.of()).build();
    }

    private static String rs256(JWTClaimsSet claims, RSAKey key) {
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                    .keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] publicKey() {
        try {
            return KEY.toRSAPublicKey().getEncoded();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Answers every session validation as active; JWT checks happen before this call. */
    private static HttpServer fakeAuth() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/internal/v1/sessions/validations", exchange -> {
                byte[] body = "{\"active\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
