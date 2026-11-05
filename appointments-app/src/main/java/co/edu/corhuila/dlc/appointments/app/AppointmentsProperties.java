package co.edu.corhuila.dlc.appointments.app;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Externalized configuration bound from the environment variables listed in .env.example. */
@ConfigurationProperties(prefix = "appointments")
public record AppointmentsProperties(String clinicTimezone, Security security, Internal internal, Auth auth,
        Patients patients, Messaging messaging) {

    /**
     * Broker URI with credentials (secret); outbox relay exchange and batch; consumed producer exchanges and the
     * bounded consumer retry.
     */
    public record Messaging(URI rabbitmqUri, String exchange, int relayBatchSize, String clinicalExchange,
            String patientsExchange, int consumerAttempts, Duration consumerBackoff) { }

    /** Trusted Auth public key (inline PEM or file), issuer and audience of staff access tokens. */
    public record Security(String jwtPublicKey, String jwtPublicKeyFile, String jwtIssuer, String jwtAudience) { }

    /** Service JWTs accepted on /internal/v1; never the staff key, issuer or audience. */
    public record Internal(String jwtPublicKey, String jwtPublicKeyFile, String jwtIssuer, String jwtAudience) { }

    /** Private Auth origin and this service's credential for POST /internal/v1/sessions/validations. */
    public record Auth(URI internalUrl, String serviceToken, String serviceTokenFile, Duration timeout) {

        public String currentServiceToken() {
            return token(serviceToken, serviceTokenFile);
        }
    }

    /** Private Patients origin and this service's credential for GET /internal/v1/patients/{id}/validation. */
    public record Patients(URI internalUrl, String serviceToken, String serviceTokenFile) {
        public String currentServiceToken() {
            return token(serviceToken, serviceTokenFile);
        }
    }

    /** Read on every call so a rotated credential file is picked up without restart. */
    private static String token(String inline, String file) {
        if (inline != null && !inline.isBlank()) return inline;
        if (file == null || file.isBlank()) return null;
        try {
            return Files.readString(Path.of(file)).trim();
        } catch (IOException unreadable) {
            return null;
        }
    }
}
