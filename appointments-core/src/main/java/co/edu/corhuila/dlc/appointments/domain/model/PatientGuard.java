package co.edu.corhuila.dlc.appointments.domain.model;

import java.util.UUID;

/** A guard as the originating service sees it; the token is never part of it. */
public record PatientGuard(UUID id, UUID patientId, UUID operationId, GuardKind kind, GuardState state) {
}
