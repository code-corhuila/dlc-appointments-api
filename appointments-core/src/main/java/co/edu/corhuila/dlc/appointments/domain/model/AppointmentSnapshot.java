package co.edu.corhuila.dlc.appointments.domain.model;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Complete stored state of an appointment, used to persist and reconstitute the aggregate. */
public record AppointmentSnapshot(UUID id, UUID patientId, UUID dentistId, OffsetDateTime startAt,
        OffsetDateTime endAt, String reason, AppointmentStatus status, ConfirmationStatus confirmationStatus,
        ReminderStatus reminderStatus, long version, Instant createdAt, UUID createdBy, Instant updatedAt,
        UUID updatedBy) { }
