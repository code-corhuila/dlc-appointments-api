package co.edu.corhuila.dlc.appointments.domain.model;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * Appointment aggregate. Scheduling supplies every DB-03B-B initial value from the verified actor and
 * the server clock; reconstitution restores stored state. Eligibility (APT-001) and overlap (APT-002)
 * remain use-case and persistence responsibilities. Lifecycle transitions are a separate increment.
 */
public final class Appointment {
    private final UUID id;
    private final UUID patientId;
    private final UUID dentistId;
    private final OffsetDateTime startAt;
    private final OffsetDateTime endAt;
    private final String reason;
    private final AppointmentStatus status;
    private final ConfirmationStatus confirmationStatus;
    private final ReminderStatus reminderStatus;
    private final long version;
    private final Instant createdAt;
    private final UUID createdBy;
    private final Instant updatedAt;
    private final UUID updatedBy;

    private Appointment(AppointmentSnapshot state) {
        this.id = state.id();
        this.patientId = state.patientId();
        this.dentistId = state.dentistId();
        this.startAt = state.startAt();
        this.endAt = state.endAt();
        this.reason = state.reason();
        this.status = state.status();
        this.confirmationStatus = state.confirmationStatus();
        this.reminderStatus = state.reminderStatus();
        this.version = state.version();
        this.createdAt = state.createdAt();
        this.createdBy = state.createdBy();
        this.updatedAt = state.updatedAt();
        this.updatedBy = state.updatedBy();
    }

    public static Appointment schedule(UUID id, UUID patientId, UUID dentistId, OffsetDateTime startAt,
                                       OffsetDateTime endAt, String reason, UUID actor, Instant now) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
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
        if (!startAt.toInstant().isAfter(now)) {
            throw new InvalidAppointmentException("startAt", "Start must be in the future.");
        }
        return new Appointment(new AppointmentSnapshot(id, patientId, dentistId, startAt, endAt, reason,
                AppointmentStatus.PROGRAMADA, ConfirmationStatus.PENDING, ReminderStatus.PENDING, 1,
                now, actor, now, actor));
    }

    /** Restores stored state; a row that breaks an invariant is corrupt, not a client error. */
    public static Appointment reconstitute(AppointmentSnapshot stored) {
        Objects.requireNonNull(stored, "stored");
        boolean complete = stored.id() != null && stored.patientId() != null && stored.dentistId() != null
                && stored.startAt() != null && stored.endAt() != null && stored.reason() != null
                && stored.status() != null && stored.confirmationStatus() != null && stored.reminderStatus() != null
                && stored.createdAt() != null && stored.createdBy() != null && stored.updatedAt() != null
                && stored.updatedBy() != null;
        if (!complete || stored.version() < 1 || !stored.startAt().toInstant().isBefore(stored.endAt().toInstant())
                || stored.updatedAt().isBefore(stored.createdAt())) {
            throw new IllegalStateException("Stored appointment state is invalid.");
        }
        return new Appointment(stored);
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw new InvalidAppointmentException(field, "Required value is missing.");
        }
    }

    public AppointmentSnapshot snapshot() {
        return new AppointmentSnapshot(id, patientId, dentistId, startAt, endAt, reason, status,
                confirmationStatus, reminderStatus, version, createdAt, createdBy, updatedAt, updatedBy);
    }

    public UUID id() { return id; }
    public UUID patientId() { return patientId; }
    public UUID dentistId() { return dentistId; }
    public OffsetDateTime startAt() { return startAt; }
    public OffsetDateTime endAt() { return endAt; }
    public String reason() { return reason; }
    /** APT-006: due time only; delivery and short-notice policy are external. */
    public OffsetDateTime reminderDueAt() { return startAt.minusHours(24); }
    public AppointmentStatus status() { return status; }
    public ConfirmationStatus confirmationStatus() { return confirmationStatus; }
    public ReminderStatus reminderStatus() { return reminderStatus; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public UUID createdBy() { return createdBy; }
    public Instant updatedAt() { return updatedAt; }
    public UUID updatedBy() { return updatedBy; }
}
