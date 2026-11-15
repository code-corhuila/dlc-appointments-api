package co.edu.corhuila.dlc.appointments.adapter.out.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR-020: Appointments exchanges client credentials at IAM for service tokens, cached until 2/3 of their life. */
class IamServiceTokenSupplierTest {
    @TempDir Path dir;
    private HttpServer iam;
    private final List<String> requests = new ArrayList<>();
    private final AtomicInteger issued = new AtomicInteger();
    private volatile int status = 200;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-08T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @BeforeEach
    void start() throws Exception {
        iam = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        iam.createContext("/internal/v1/service-tokens", exchange -> {
            requests.add(exchange.getRequestHeaders().getFirst("Authorization") + " "
                + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = ("{\"accessToken\":\"tok-" + issued.incrementAndGet()
                + "\",\"tokenType\":\"Bearer\",\"expiresIn\":900,\"scope\":\"patients:validate\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 200 ? body.length : -1);
            if (status == 200) exchange.getResponseBody().write(body);
            exchange.close();
        });
        iam.start();
    }

    @AfterEach
    void stop() {
        iam.stop(0);
    }

    private IamServiceTokenSupplier supplier() throws Exception {
        Path secret = Files.writeString(dir.resolve("secret"), "s3cret\n");
        return new IamServiceTokenSupplier(URI.create("http://127.0.0.1:" + iam.getAddress().getPort()),
            "appointments-api", secret, "patients-api", "patients:validate", Duration.ofSeconds(2), clock);
    }

    @Test
    void requestsWithBasicCredentialsAndReusesTheTokenUntilTwoThirdsOfItsLife() throws Exception {
        var tokens = supplier();
        assertEquals("tok-1", tokens.get());
        assertEquals("Basic " + Base64.getEncoder().encodeToString("appointments-api:s3cret".getBytes())
            + " {\"audience\":\"patients-api\",\"scope\":\"patients:validate\"}", requests.get(0));
        now.set(now.get().plusSeconds(599));
        assertEquals("tok-1", tokens.get());
        now.set(now.get().plusSeconds(1));
        assertEquals("tok-2", tokens.get());
        assertEquals(2, requests.size());
    }

    @Test
    void aRejectedOrUnreachableIssuerYieldsNoCredential() throws Exception {
        var tokens = supplier();
        status = 401;
        assertNull(tokens.get());
        iam.stop(0);
        assertNull(tokens.get());
    }
}
