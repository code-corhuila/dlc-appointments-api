package co.edu.corhuila.dlc.appointments.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Initial scheduling state from the Appointments contract.
 * Eligibility (APT-001) and overlap (APT-002) checks belong to the use case.
 * Lifecycle transitions and persistence reconstitution are separate increments.
 */
public final class Appointment {
    private final UUID id;
    private final UUID patientId;
    private final UUID dentistId;
    private final OffsetDateTime startAt;
    private final OffsetDateTime endAt;
    private final String reason;

    private Appointment(UUID id, UUID patientId, UUID dentistId,
                        OffsetDateTime startAt, OffsetDateTime endAt, String reason) {
        require(id, "id");
        require(patientId, "patientId");
        require(dentistId, "dentistId");
        require(startAt, "startAt");
        require(endAt, "endAt");
        require(reason, "reason");
        if (!startAt.toInstant().isBefore(endAt.toInstant())) {
            throw new InvalidAppointmentException("endAt", "End must be after start.");
        }
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 500) {
            throw new InvalidAppointmentException("reason", "Reason must contain 1 to 500 characters.");
        }
        this.id = id;
        this.patientId = patientId;
        this.dentistId = dentistId;
        this.startAt = startAt;
        this.endAt = endAt;
        this.reason = reason;
    }

    public static Appointment schedule(UUID id, UUID patientId, UUID dentistId,
                                       OffsetDateTime startAt, OffsetDateTime endAt, String reason) {
        return new Appointment(id, patientId, dentistId, startAt, endAt, reason);
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw new InvalidAppointmentException(field, "Required value is missing.");
        }
    }

    public UUID id() { return id; }
    public UUID patientId() { return patientId; }
    public UUID dentistId() { return dentistId; }
    public OffsetDateTime startAt() { return startAt; }
    public OffsetDateTime endAt() { return endAt; }
    public String reason() { return reason; }
    /** APT-006: due time only; delivery and short-notice policy are external. */
    public OffsetDateTime reminderDueAt() { return startAt.minusHours(24); }
    public AppointmentStatus status() { return AppointmentStatus.PROGRAMADA; }
}

