package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads Auth's RSA public key from JWT_PUBLIC_KEY (single-line PEM with literal \n, as a .env stores it)
 * or from JWT_PUBLIC_KEY_FILE. A missing or unreadable key stops startup; no private key is ever read.
 */
public final class RsaPublicKeys {
    private RsaPublicKeys() { }

    public static RSAPublicKey fromConfiguration(String inlinePem, String pemFile) {
        String pem;
        if (inlinePem != null && !inlinePem.isBlank()) {
            pem = inlinePem.replace("\\n", "\n");
        } else if (pemFile != null && !pemFile.isBlank()) {
            try {
                pem = Files.readString(Path.of(pemFile));
            } catch (IOException | RuntimeException unreadable) {
                throw new IllegalStateException("JWT_PUBLIC_KEY_FILE cannot be read.");
            }
        } else {
            throw new IllegalStateException("JWT_PUBLIC_KEY or JWT_PUBLIC_KEY_FILE is required.");
        }
        return parse(pem);
    }

    private static RSAPublicKey parse(String pem) {
        try {
            String body = pem.replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            var spec = new X509EncodedKeySpec(Base64.getDecoder().decode(body));
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException invalid) {
            throw new IllegalStateException("The configured JWT public key is not a valid RSA public key.");
        }
    }
}
