package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.port.out.IdGenerator;
import co.edu.corhuila.dlc.appointments.application.port.out.PatientGuardStore;
import co.edu.corhuila.dlc.appointments.domain.model.GuardKind;
import co.edu.corhuila.dlc.appointments.domain.model.GuardState;
import co.edu.corhuila.dlc.appointments.domain.model.PatientGuard;
import co.edu.corhuila.dlc.appointments.domain.model.PatientGuardNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * ADR-017 patient guards for Patients (deactivation) and Clinical (activity). Every acquisition issues a fresh
 * 256-bit token returned once; Appointments keeps only its SHA-256 hash. The caller is the verified service subject.
 */
public final class PatientGuards {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PatientGuardStore store;
    private final IdGenerator ids;
    private final Clock clock;

    public record Acquired(PatientGuard guard, String token) { }

    public PatientGuards(PatientGuardStore store, IdGenerator ids, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Acquired acquire(UUID patientId, UUID operationId, String caller, GuardKind kind) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        PatientGuard guard = store.acquire(Objects.requireNonNull(patientId, "patientId"),
                Objects.requireNonNull(operationId, "operationId"), Objects.requireNonNull(caller, "caller"),
                Objects.requireNonNull(kind, "kind"), ids.newId(), hash(token), clock.instant());
        return new Acquired(guard, token);
    }

    public PatientGuard byOperation(UUID operationId, String caller) {
        return store.byOperation(operationId, caller).orElseThrow(PatientGuardNotFoundException::new);
    }

    public PatientGuard byId(UUID guardId, String caller) {
        return store.byId(guardId, caller).orElseThrow(PatientGuardNotFoundException::new);
    }

    public PatientGuard resolve(UUID guardId, String caller, String token, GuardState outcome) {
        if (outcome == GuardState.OPEN) throw new IllegalArgumentException("Outcome must be FINALIZED or ABORTED.");
        return store.resolve(guardId, caller, hash(Objects.requireNonNull(token, "token")), outcome, clock.instant());
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
