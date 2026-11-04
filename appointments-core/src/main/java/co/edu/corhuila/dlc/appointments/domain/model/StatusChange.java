package co.edu.corhuila.dlc.appointments.domain.model;

import java.time.OffsetDateTime;

/**
 * Immutable audit facts of one transition. The previous/new interval is present only for RESCHEDULE;
 * override is true only when an Administrator used it inside the 24-hour window (APT-004).
 */
public record StatusChange(LifecycleOperation operation, AppointmentStatus fromStatus, AppointmentStatus toStatus,
        OffsetDateTime previousStartAt, OffsetDateTime previousEndAt, OffsetDateTime newStartAt,
        OffsetDateTime newEndAt, String reason, boolean override) {
}
