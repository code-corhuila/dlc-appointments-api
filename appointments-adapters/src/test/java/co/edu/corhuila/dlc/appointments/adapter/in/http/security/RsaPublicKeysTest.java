package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RsaPublicKeysTest {
    private static RSAPublicKey key;
    private static String pem;

    @BeforeAll
    static void generate() throws Exception {
        key = new RSAKeyGenerator(2048).generate().toRSAPublicKey();
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded());
        pem = "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    @Test
    void parsesSingleLinePemWithLiteralEscapedNewlinesAsStoredInDotEnv() {
        String dotEnvValue = pem.replace("\n", "\\n");
        assertEquals(key.getModulus(), RsaPublicKeys.fromConfiguration(dotEnvValue, null).getModulus());
    }

    @Test
    void readsPemFromFileWhenInlineValueIsAbsent(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("auth-public.pem"), pem);
        assertEquals(key.getModulus(), RsaPublicKeys.fromConfiguration("", file.toString()).getModulus());
    }

    @Test
    void missingOrInvalidKeyPreventsStartup() {
        assertThrows(IllegalStateException.class, () -> RsaPublicKeys.fromConfiguration("", ""));
        assertThrows(IllegalStateException.class, () -> RsaPublicKeys.fromConfiguration(
                "-----BEGIN PUBLIC KEY-----\\nnot-base64\\n-----END PUBLIC KEY-----", null));
        assertThrows(IllegalStateException.class, () -> RsaPublicKeys.fromConfiguration(null, "/does/not/exist.pem"));
    }
}
