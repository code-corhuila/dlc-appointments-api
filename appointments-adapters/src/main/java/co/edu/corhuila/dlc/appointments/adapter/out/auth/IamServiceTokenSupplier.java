package co.edu.corhuila.dlc.appointments.adapter.out.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * ADR-020: a service JWT issued by IAM for one audience through client credentials
 * (POST /internal/v1/service-tokens), cached until two thirds of its lifetime. The client secret is reread from
 * its file on each renewal so it can rotate. Like the file credential, a failure yields no token (null).
 */
public final class IamServiceTokenSupplier implements Supplier<String> {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http;
    private final URI endpoint;
    private final String clientId;
    private final Path secretFile;
    private final String body;
    private final Duration timeout;
    private final Clock clock;
    private String cached;
    private Instant renewAt = Instant.MIN;

    public IamServiceTokenSupplier(URI iamUrl, String clientId, Path secretFile, String audience, String scope,
                                   Duration timeout, Clock clock) {
        this.endpoint = Objects.requireNonNull(iamUrl, "iamUrl").resolve("/internal/v1/service-tokens");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.secretFile = Objects.requireNonNull(secretFile, "secretFile");
        var request = new LinkedHashMap<String, String>();
        request.put("audience", audience);
        request.put("scope", scope);
        try {
            this.body = JSON.writeValueAsString(request);
        } catch (Exception impossible) {
            throw new IllegalArgumentException(impossible);
        }
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public synchronized String get() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(renewAt)) return cached;
        try {
            String secret = Files.readString(secretFile, StandardCharsets.UTF_8).strip();
            String basic = Base64.getEncoder().encodeToString((clientId + ":" + secret)
                .getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Authorization", "Basic " + basic).header("Content-Type", "application/json")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            JsonNode node = JSON.readTree(response.body());
            JsonNode token = node.get("accessToken");
            JsonNode expiresIn = node.get("expiresIn");
            if (token == null || !token.isTextual() || token.asText().isBlank()
                || expiresIn == null || !expiresIn.canConvertToLong() || expiresIn.asLong() < 3) {
                return null;
            }
            cached = token.asText();
            renewAt = now.plusSeconds(expiresIn.asLong() * 2 / 3);
            return cached;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unavailable) {
            return null;
        }
    }
}
