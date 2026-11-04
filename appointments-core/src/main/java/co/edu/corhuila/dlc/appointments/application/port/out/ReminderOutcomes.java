package co.edu.corhuila.dlc.appointments.application.port.out;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-005 worker contract. {@code appointmentVersion} is the version of the appointment's live reminder, the one
 * AppointmentReminderRequested carried: a confirmation keeps it, a reschedule replaces it, a cancellation shows in
 * the status. {@code record} returns false for an identical repeat and raises ReminderOutcomeConflictException for
 * an unknown, obsolete or invalidated delivery (STALE) or a different final outcome (CONFLICTING).
 */
public interface ReminderOutcomes {
    Optional<Details> details(UUID appointmentId);

    boolean record(Outcome outcome);

    record Details(UUID appointmentId, UUID patientId, long appointmentVersion, OffsetDateTime startAt,
            OffsetDateTime endAt, String status) { }

    record Outcome(UUID appointmentId, UUID deliveryId, long appointmentVersion, String status, int attempts,
            Instant occurredAt, String providerMessageId, String failureCode) { }
}
