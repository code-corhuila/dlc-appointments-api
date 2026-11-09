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
    /** Any staff id with this prefix is an active Dentist in the fake Auth owner; tests use fresh ones. */
    static final String DENTIST_PREFIX = "d0000000-";
    private static final RSAKey KEY = generate();
    private static final RSAKey OTHER_KEY = generate();
    static final String SERVICE_ISSUER = "https://services.test.local";
    static final String SERVICE_AUDIENCE = "dlc-appointments-test";
    private static final RSAKey SERVICE_KEY = generate();
    private static final HttpServer AUTH = fakeAuth();

    private TestIdentity() { }

    static void register(DynamicPropertyRegistry registry) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(publicKey());
        String pem = "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----";
        registry.add("appointments.security.jwt-public-key", () -> pem.replace("\n", "\\n"));
        registry.add("appointments.security.jwt-issuer", () -> ISSUER);
        registry.add("appointments.security.jwt-audience", () -> AUDIENCE);
        String service = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(encoded(SERVICE_KEY));
        registry.add("appointments.internal.jwt-public-key", () -> ("-----BEGIN PUBLIC KEY-----\n" + service
                + "\n-----END PUBLIC KEY-----").replace("\n", "\\n"));
        registry.add("appointments.internal.jwt-issuer", () -> SERVICE_ISSUER);
        // HTTP tests assert exact reminder rows; the periodic pass is exercised by its own tests.
        registry.add("appointments.reminders.enabled", () -> "false");
        registry.add("appointments.messaging.relay-enabled", () -> "false");
        registry.add("appointments.messaging.consumers-enabled", () -> "false");
        registry.add("appointments.internal.jwt-audience", () -> SERVICE_AUDIENCE);
        registry.add("appointments.auth.internal-url",
                () -> "http://localhost:" + AUTH.getAddress().getPort());
        registry.add("appointments.auth.service-token", () -> "synthetic-service-token");
        registry.add("appointments.patients.internal-url",
                () -> "http://localhost:" + AUTH.getAddress().getPort());
        registry.add("appointments.patients.service-token", () -> "synthetic-patients-token");
        // Unreachable on purpose: HTTP tests that need the database configure a real one.
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:1/unreachable");
    }

    static String bearer(String staffId, String... roles) {
        return "Bearer " + rs256(claims(staffId, Instant.now().plusSeconds(600), roles), KEY);
    }

    /** A short-lived service JWT for /internal/v1 with the given space-separated scopes. */
    static String service(String subject, String... scopes) {
        Instant now = Instant.now();
        return "Bearer " + rs256(new JWTClaimsSet.Builder().issuer(SERVICE_ISSUER).audience(SERVICE_AUDIENCE)
                .subject(subject).issueTime(Date.from(now.minusSeconds(5))).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", String.join(" ", scopes)).build(), SERVICE_KEY);
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
        return encoded(KEY);
    }

    private static byte[] encoded(RSAKey key) {
        try {
            return key.toRSAPublicKey().getEncoded();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String json)
            throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
        if (status == 200) exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    static final String ACTIVE_PATIENT = "a9900000-0000-4000-8000-0000000000e1";
    static final String INACTIVE_PATIENT = "a9900000-0000-4000-8000-0000000000e9";
    static final String UNVERIFIABLE_PATIENT = "a9900000-0000-4000-8000-0000000000e8";

    /**
     * Fake Auth and Patients owners: every session is active; DENTIST is an active Dentist and other staff are
     * unknown; INACTIVE_PATIENT is inactive, UNVERIFIABLE_PATIENT's owner answers 503, any other patient is active.
     */
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
            server.createContext("/internal/v1/staff/", exchange -> {
                String id = exchange.getRequestURI().getPath().split("/")[4];
                respond(exchange, DENTIST.equals(id) || id.startsWith(DENTIST_PREFIX) ? 200 : 404,
                        "{\"staffId\":\"" + id + "\",\"active\":true,\"roles\":[\"DENTIST\"]}");
            });
            server.createContext("/internal/v1/patients/", exchange -> {
                String id = exchange.getRequestURI().getPath().split("/")[4];
                respond(exchange, UNVERIFIABLE_PATIENT.equals(id) ? 503 : 200,
                        "{\"patientId\":\"" + id + "\",\"active\":" + !INACTIVE_PATIENT.equals(id) + ",\"version\":1}");
            });
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            return server;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
