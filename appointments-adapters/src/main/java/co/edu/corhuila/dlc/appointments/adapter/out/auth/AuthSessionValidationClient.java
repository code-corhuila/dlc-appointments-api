package co.edu.corhuila.dlc.appointments.adapter.out.auth;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Isolated Auth query; caller must first verify the staff JWT locally.
 * Inject a dedicated trusted transport with redirects/retries and sensitive logging disabled,
 * explicit connection/read deadlines, and HTTPS outside local development.
 * This class cannot inspect or override the injected transport's redirect policy.
 * App owns production configuration and the real service credential provider (identity:validate).
 * No credential issuance, session cache, authentication decision or authorization is provided.
 */
public final class AuthSessionValidationClient {
    private static final ObjectReader JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final RestClient http;
    private final URI endpoint;
    private final Supplier<String> serviceCredential;

    public AuthSessionValidationClient(RestClient http, URI trustedAuthOrigin, Supplier<String> serviceCredential) {
        this.http = Objects.requireNonNull(http, "http");
        this.serviceCredential = Objects.requireNonNull(serviceCredential, "serviceCredential");
        Objects.requireNonNull(trustedAuthOrigin, "trustedAuthOrigin");
        String path = trustedAuthOrigin.getRawPath();
        if (trustedAuthOrigin.getHost() == null || trustedAuthOrigin.getRawUserInfo() != null
                || trustedAuthOrigin.getRawQuery() != null || trustedAuthOrigin.getRawFragment() != null
                || !(path == null || path.isEmpty() || path.equals("/"))
                || !("https".equalsIgnoreCase(trustedAuthOrigin.getScheme())
                || "http".equalsIgnoreCase(trustedAuthOrigin.getScheme()))) {
            throw new IllegalArgumentException("A trusted Auth origin is required.");
        }
        this.endpoint = trustedAuthOrigin.resolve("/internal/v1/sessions/validations");
    }

    /** Only a successful, contract-valid response may return false. Tokens are never retained. */
    public boolean isActive(String locallyVerifiedAccessToken) {
        if (locallyVerifiedAccessToken == null || locallyVerifiedAccessToken.isBlank()) throw unavailable();
        try {
            String credential = serviceCredential.get();
            if (credential == null || credential.isBlank()) throw unavailable();
            return http.post().uri(endpoint)
                    .headers(headers -> headers.setBearerAuth(credential))
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(new SessionRequest(locallyVerifiedAccessToken))
                    .exchange((request, response) -> {
                        if (response.getStatusCode().value() != 200
                                || !MediaType.APPLICATION_JSON.equalsTypeAndSubtype(response.getHeaders().getContentType())) {
                            throw unavailable();
                        }
                        JsonNode result = JSON.readValue(response.getBody());
                        if (result == null || !result.isObject() || result.size() != 1
                                || !result.has("active") || !result.get("active").isBoolean()) {
                            throw unavailable();
                        }
                        return result.get("active").booleanValue();
                    });
        } catch (RestClientException | InvalidMediaTypeException failure) {
            // Do not retain bodies, messages or causes from transport/parsing failures.
            throw unavailable();
        }
    }

    record SessionRequest(String accessToken) {
        @Override public String toString() { return "SessionRequest[redacted]"; }
    }

    private static AuthSessionValidationUnavailableException unavailable() {
        return new AuthSessionValidationUnavailableException();
    }
}