package co.edu.corhuila.dlc.appointments.app;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Externalized configuration bound from the environment variables listed in .env.example. */
@ConfigurationProperties(prefix = "appointments")
public record AppointmentsProperties(Security security, Auth auth) {

    /** Trusted Auth public key (inline PEM or file), issuer and audience of staff access tokens. */
    public record Security(String jwtPublicKey, String jwtPublicKeyFile, String jwtIssuer, String jwtAudience) { }

    /** Private Auth origin and this service's credential for POST /internal/v1/sessions/validations. */
    public record Auth(URI internalUrl, String serviceToken, String serviceTokenFile, Duration timeout) {

        /** Read on every call so a rotated credential file is picked up without restart. */
        public String currentServiceToken() {
            if (serviceToken != null && !serviceToken.isBlank()) return serviceToken;
            if (serviceTokenFile == null || serviceTokenFile.isBlank()) return null;
            try {
                return Files.readString(Path.of(serviceTokenFile)).trim();
            } catch (IOException unreadable) {
                return null;
            }
        }
    }
}
