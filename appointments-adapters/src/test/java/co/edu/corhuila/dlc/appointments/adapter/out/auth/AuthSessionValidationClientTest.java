package co.edu.corhuila.dlc.appointments.adapter.out.auth;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.system.*;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

@ExtendWith(OutputCaptureExtension.class)
class AuthSessionValidationClientTest {
    private static final URI ORIGIN = URI.create("https://auth.test");
    private static final String STAFF = "synthetic-staff-secret";
    private static final String SERVICE = "synthetic-service-secret";
    private MockRestServiceServer server;
    private RestClient http;
    private AuthSessionValidationClient client;

    @BeforeEach
    void setup() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        http = builder.build();
        client = new AuthSessionValidationClient(http, ORIGIN, () -> SERVICE);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sendsExactContractAndReturnsBoolean(boolean active) {
        server.expect(requestTo(ORIGIN + "/internal/v1/sessions/validations"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + SERVICE))
                .andExpect(header(HttpHeaders.CONTENT_TYPE, "application/json"))
                .andExpect(content().json("{\"accessToken\":\"" + STAFF + "\"}", true))
                .andRespond(withSuccess("{\"active\":" + active + "}", MediaType.APPLICATION_JSON));
        assertEquals(active, client.isActive(STAFF));
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingStaffTokenWithoutSending(String token) {
        unavailable(() -> client.isActive(token));
        server.verify();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingServiceCredentialWithoutSending(String token) {
        var missing = new AuthSessionValidationClient(http, ORIGIN, () -> token);
        unavailable(() -> missing.isActive(STAFF));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"active\":null}", "{\"active\":\"true\"}",
            "{\"active\":1}", "{\"active\":true,\"extra\":0}", "", "{broken",
            "{\"active\":true} {}", "{\"active\":false,\"active\":true}"})
    void rejectsInvalidResponse(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        unavailable(() -> client.isActive(STAFF));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 204, 301, 302, 307, 308, 400, 401, 403, 409, 500, 503})
    void rejectsEveryUnexpectedStatus(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatusCode.valueOf(status))
                .body(STAFF + SERVICE).header(HttpHeaders.LOCATION, "https://untrusted.invalid/"));
        unavailable(() -> client.isActive(STAFF));
        server.verify();
    }

    @Test
    void rejectsNonJsonAndMissingContentType() {
        server.expect(anything()).andRespond(withSuccess("{\"active\":true}", MediaType.TEXT_PLAIN));
        server.expect(anything()).andRespond(withSuccess("{\"active\":true}", null));
        unavailable(() -> client.isActive(STAFF));
        unavailable(() -> client.isActive(STAFF));
        server.verify();
    }

    @Test
    void rejectsMalformedMediaTypeWithoutExposingHeader() {
        server.expect(anything()).andRespond(withSuccess("{\"active\":true}", null)
                .header(HttpHeaders.CONTENT_TYPE, STAFF + SERVICE));
        unavailable(() -> client.isActive(STAFF));
        server.verify();
    }

    @Test
    void neverUsesCachedSuccessAfterDependencyFailure(CapturedOutput output) {
        server.expect(anything()).andRespond(withSuccess("{\"active\":true}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withException(new SocketTimeoutException(STAFF + SERVICE)));
        assertTrue(client.isActive(STAFF));
        unavailable(() -> client.isActive(STAFF));
        assertFalse(output.getAll().contains(STAFF));
        assertFalse(output.getAll().contains(SERVICE));
        assertFalse(client.toString().contains(STAFF));
        assertFalse(client.toString().contains(SERVICE));
        assertFalse(new AuthSessionValidationClient.SessionRequest(STAFF).toString().contains(STAFF));
        server.verify();
    }

    @Test
    void realTransportRejectsRedirectTimesOutAndRejectsClosedConnection() throws Exception {
        var local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var origin = URI.create("http://127.0.0.1:" + local.getAddress().getPort());
        var slow = new AtomicBoolean();
        var targetCalls = new AtomicInteger();
        var validationCalls = new AtomicInteger();
        var release = new CountDownLatch(1);
        local.createContext("/internal/v1/sessions/validations", exchange -> {
            validationCalls.incrementAndGet();
            if (slow.get()) {
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } else {
                exchange.getResponseHeaders().set("Location", origin + "/target");
                exchange.sendResponseHeaders(307, -1);
            }
            exchange.close();
        });
        local.createContext("/target", exchange -> { targetCalls.incrementAndGet(); exchange.close(); });
        try (var transport = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(1)).build()) {
            var factory = new JdkClientHttpRequestFactory(transport);
            factory.setReadTimeout(Duration.ofMillis(250));
            var live = new AuthSessionValidationClient(RestClient.builder().requestFactory(factory).build(),
                    origin, () -> SERVICE);
            local.start();
            unavailable(() -> live.isActive(STAFF));
            assertEquals(0, targetCalls.get());
            assertEquals(1, validationCalls.get());
            slow.set(true);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> unavailable(() -> live.isActive(STAFF)));
            assertEquals(2, validationCalls.get());
            release.countDown();
            local.stop(0);
            unavailable(() -> live.isActive(STAFF));
            assertEquals(2, validationCalls.get());
        } finally {
            release.countDown();
            local.stop(0);
        }
    }

    private void unavailable(Runnable operation) {
        var failure = assertThrows(AuthSessionValidationUnavailableException.class, operation::run);
        assertEquals("Auth session validation is unavailable.", failure.getMessage());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(STAFF));
        assertFalse(failure.toString().contains(SERVICE));
    }
}