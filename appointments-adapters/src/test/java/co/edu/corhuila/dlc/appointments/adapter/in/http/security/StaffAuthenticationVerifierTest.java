package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import co.edu.corhuila.dlc.appointments.adapter.out.auth.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.system.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class StaffAuthenticationVerifierTest {
    private static final long NOW = 2000000000L;
    private static final String SERVICE = "synthetic-service-secret";
    private static RSAKey key;
    private static RSAKey otherKey;
    private static StaffJwtSignatureVerifier jwt;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>("{\"active\":true}");
    private final AtomicReference<String> credential = new AtomicReference<>(SERVICE);
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> bearer = new AtomicReference<>();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private volatile boolean hold;
    private HttpServer server;
    private HttpClient transport;
    private RestClient http;
    private URI origin;
    private StaffAuthenticationVerifier verifier;

    @BeforeAll
    static void keys() throws Exception {
        key = new RSAKeyGenerator(2048).generate();
        otherKey = new RSAKeyGenerator(2048).generate();
        jwt = new StaffJwtSignatureVerifier(key.toRSAPublicKey(), "test-issuer", "test-audience",
                Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        server.createContext("/internal/v1/sessions/validations", exchange -> {
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            bearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            entered.countDown();
            if (hold) {
                try { release.await(4, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            exchange.close();
        });
        server.start();
        transport = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(1)).build();
        var factory = new JdkClientHttpRequestFactory(transport);
        factory.setReadTimeout(Duration.ofSeconds(3));
        http = RestClient.builder().requestFactory(factory).build();
        verifier = coordinator(credential::get);
    }

    @AfterEach
    void close() {
        release.countDown();
        server.stop(0);
        transport.close();
    }

    @Test
    void returnsLocallyVerifiedClaimsOnlyAfterActiveSession() throws Exception {
        String token = validToken();
        assertEquals(jwt.verifySignatureAndClaims(token).getClaims(), verifier.verify(token).getClaims());
        assertEquals(1, calls.get());
        assertEquals("Bearer " + SERVICE, bearer.get());
        assertEquals(token, json.readTree(received.get()).get("accessToken").textValue());
        assertEquals(1, json.readTree(received.get()).size());
    }

    @Test
    void distinguishesInactiveSessionFromInvalidJwtAndUnavailableAuth() throws Exception {
        response.set("{\"active\":false}");
        var failure = assertThrows(StaffAuthenticationVerifier.InactiveSessionException.class,
                () -> verifier.verify(validToken()));
        assertEquals("The staff session is inactive.", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(1, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "expired", "issuer", "audience", "claims", "malformed"})
    void neverCallsAuthAfterInvalidJwt(String scenario) throws Exception {
        var claims = claims();
        if (scenario.equals("expired")) claims.put("exp", NOW);
        if (scenario.equals("issuer")) claims.put("iss", "wrong");
        if (scenario.equals("audience")) claims.put("aud", "wrong");
        if (scenario.equals("claims")) claims.remove("roles");
        String token = scenario.equals("malformed") ? "malformed-secret-token"
                : signed(claims, scenario.equals("signature") ? otherKey : key);
        var failure = assertThrows(BadJwtException.class, () -> verifier.verify(token));
        assertFalse(failure instanceof StaffAuthenticationVerifier.InactiveSessionException);
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(token));
        assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingTokenWithoutAuth(String token) {
        assertThrows(BadJwtException.class, () -> verifier.verify(token));
        assertEquals(0, calls.get());
    }

    @Test
    void propagatesAuthUnavailable() throws Exception {
        status.set(503);
        assertThrows(AuthSessionValidationUnavailableException.class, () -> verifier.verify(validToken()));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsInvalidAuthResponse() throws Exception {
        response.set("{\"active\":\"true\"}");
        assertThrows(AuthSessionValidationUnavailableException.class, () -> verifier.verify(validToken()));
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsMissingServiceCredentialWithoutRequest() throws Exception {
        credential.set(null);
        assertThrows(AuthSessionValidationUnavailableException.class, () -> verifier.verify(validToken()));
        assertEquals(0, calls.get());
    }

    @Test
    void successDoesNotBypassLaterRevocationOrFailure(CapturedOutput output) throws Exception {
        String token = validToken();
        assertNotNull(verifier.verify(token));
        response.set("{\"active\":false}");
        assertThrows(StaffAuthenticationVerifier.InactiveSessionException.class, () -> verifier.verify(token));
        status.set(503);
        response.set(token + SERVICE);
        var failure = assertThrows(AuthSessionValidationUnavailableException.class, () -> verifier.verify(token));
        assertEquals(3, calls.get());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(token));
        assertFalse(failure.toString().contains(SERVICE));
        assertFalse(output.getAll().contains(token));
        assertFalse(output.getAll().contains(SERVICE));
        assertFalse(verifier.toString().contains(token));
    }

    @Test
    void neverReturnsClaimsWhileAuthHasNotAnswered() throws Exception {
        hold = true;
        String token = validToken();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> verifier.verify(token));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertFalse(result.isDone());
            } finally { release.countDown(); }
            assertEquals(jwt.verifySignatureAndClaims(token).getClaims(), result.get(2, TimeUnit.SECONDS).getClaims());
        }
    }

    @Test
    void programmingFailureCannotBecomeSuccessfulAuthentication() throws Exception {
        var defect = new IllegalStateException("provider defect");
        var broken = coordinator(() -> { throw defect; });
        assertSame(defect, assertThrows(IllegalStateException.class, () -> broken.verify(validToken())));
        assertEquals(0, calls.get());
    }

    private StaffAuthenticationVerifier coordinator(Supplier<String> credentials) {
        return new StaffAuthenticationVerifier(jwt, new AuthSessionValidationClient(http, origin, credentials));
    }

    private static String validToken() throws Exception {
        return signed(claims(), key);
    }

    private static Map<String, Object> claims() {
        return new HashMap<>(Map.of("sub", "233665d0-c575-4ac1-8891-e96c0811b64a",
                "iss", "test-issuer", "aud", List.of("test-audience"), "iat", NOW, "exp", NOW + 3600,
                "sid", "opaque-session", "jti", "synthetic-jti", "roles", List.of("ADMINISTRATOR"), "permissions", List.of()));
    }

    private static String signed(Map<String, Object> claims, RSAKey signingKey) throws Exception {
        var token = new JWSObject(new JWSHeader(JWSAlgorithm.RS256), new Payload(claims));
        token.sign(new RSASSASigner(signingKey));
        return token.serialize();
    }
}