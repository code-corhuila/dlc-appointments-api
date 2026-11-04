package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.domain.model.GuardKind;
import co.edu.corhuila.dlc.appointments.domain.model.GuardState;
import co.edu.corhuila.dlc.appointments.domain.model.PatientGuard;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * D9 / ADR-017 guards over patient_gates and patient_guards. Rules violations raise AppointmentConflictException
 * (409); only hashes of tokens are ever stored or compared.
 */
public interface PatientGuardStore {
    /** Same patient, operationId and caller returns the same guard with {@code tokenHash} as its new token. */
    PatientGuard acquire(UUID patientId, UUID operationId, String caller, GuardKind kind, UUID newGuardId,
                         String tokenHash, Instant now);

    Optional<PatientGuard> byOperation(UUID operationId, String caller);

    Optional<PatientGuard> byId(UUID guardId, String caller);

    /** OPEN → outcome with the current token; the same outcome again is answered as is. */
    PatientGuard resolve(UUID guardId, String caller, String tokenHash, GuardState outcome, Instant now);
}
