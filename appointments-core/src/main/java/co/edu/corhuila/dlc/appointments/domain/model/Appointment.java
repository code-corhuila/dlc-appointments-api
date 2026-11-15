package co.edu.corhuila.dlc.appointments.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Appointment aggregate. Scheduling supplies every DB-03B-B initial value from the verified actor and
 * the server clock; reconstitution restores stored state. Eligibility (APT-001) and overlap (APT-002)
 * remain use-case and persistence responsibilities. Each lifecycle transition (HU-APT-002) yields the next
 * version and its audit facts.
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
    private final String typeCode;

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
        this.typeCode = state.typeCode();
    }

    public static Appointment schedule(UUID id, UUID patientId, UUID dentistId, OffsetDateTime startAt,
                                       OffsetDateTime endAt, String reason, UUID actor, Instant now) {
        return schedule(id, patientId, dentistId, startAt, endAt, reason, actor, now, null);
    }

    /** HU-APT-006: the type code is optional; its catalog membership is checked by the scheduling use case. */
    public static Appointment schedule(UUID id, UUID patientId, UUID dentistId, OffsetDateTime startAt,
                                       OffsetDateTime endAt, String reason, UUID actor, Instant now,
                                       String typeCode) {
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
                now, actor, now, actor, typeCode));
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

    /** PROGRAMADA → CONFIRMADA, recording the clinic channel staff used. */
    public Transition confirm(ConfirmationChannel channel, UUID actor, Instant now) {
        Objects.requireNonNull(channel, "channel");
        requireFrom(actor, now, AppointmentStatus.PROGRAMADA);
        return move(LifecycleOperation.CONFIRM, AppointmentStatus.CONFIRMADA, ConfirmationStatus.CONFIRMED,
                reminderStatus, startAt, endAt, null, false, actor, now);
    }

    /** APT-003/004/010: PROGRAMADA/CONFIRMADA → CANCELADA outside the 24 h window or with Administrator override. */
    public Transition cancel(String reason, boolean override, StaffRole role, UUID actor, Instant now) {
        requireFrom(actor, now, AppointmentStatus.PROGRAMADA, AppointmentStatus.CONFIRMADA);
        String audited = reason(reason);
        boolean used = window(override, role, now);
        return move(LifecycleOperation.CANCEL, AppointmentStatus.CANCELADA, confirmationStatus, reminderStatus,
                startAt, endAt, audited, used, actor, now);
    }

    /** APT-005/010: moves the interval, returns to PROGRAMADA/PENDING and recalculates the reminder. */
    public Transition reschedule(OffsetDateTime newStartAt, OffsetDateTime newEndAt, String reason, boolean override,
                                 StaffRole role, UUID actor, Instant now) {
        requireFrom(actor, now, AppointmentStatus.PROGRAMADA, AppointmentStatus.CONFIRMADA);
        require(newStartAt, "startAt");
        require(newEndAt, "endAt");
        if (!newStartAt.toInstant().isBefore(newEndAt.toInstant())) {
            throw new InvalidAppointmentException("endAt", "End must be after start.");
        }
        if (!newStartAt.toInstant().isAfter(now)) {
            throw new InvalidAppointmentException("startAt", "Start must be in the future.");
        }
        if (newStartAt.toInstant().equals(startAt.toInstant()) && newEndAt.toInstant().equals(endAt.toInstant())) {
            throw new InvalidAppointmentException("startAt", "The new interval must differ from the current one.");
        }
        String audited = reason(reason);
        boolean used = window(override, role, now);
        return move(LifecycleOperation.RESCHEDULE, AppointmentStatus.PROGRAMADA, ConfirmationStatus.PENDING,
                ReminderStatus.PENDING, newStartAt, newEndAt, audited, used, actor, now);
    }

    /** CONFIRMADA → EN_ATENCION; grants no clinical access by itself. */
    public Transition startAttention(UUID actor, Instant now) {
        requireFrom(actor, now, AppointmentStatus.CONFIRMADA);
        return move(LifecycleOperation.START_ATTENTION, AppointmentStatus.EN_ATENCION, confirmationStatus,
                reminderStatus, startAt, endAt, null, false, actor, now);
    }

    /** APT-011: PROGRAMADA/CONFIRMADA → NO_ASISTIO, only once the appointment has ended. */
    public Transition markNoShow(String reason, UUID actor, Instant now) {
        requireFrom(actor, now, AppointmentStatus.PROGRAMADA, AppointmentStatus.CONFIRMADA);
        String audited = reason(reason);
        if (now.isBefore(endAt.toInstant())) {
            throw new AppointmentConflictException("A no-show can be recorded only after the appointment ends.");
        }
        return move(LifecycleOperation.NO_SHOW, AppointmentStatus.NO_ASISTIO, confirmationStatus, reminderStatus,
                startAt, endAt, audited, false, actor, now);
    }

    /** APT-009/012: EN_ATENCION → FINALIZADA; the care-completion declaration is checked by the use case. */
    public Transition complete(UUID actor, Instant now) {
        requireFrom(actor, now, AppointmentStatus.EN_ATENCION);
        return move(LifecycleOperation.COMPLETE, AppointmentStatus.FINALIZADA, confirmationStatus, reminderStatus,
                startAt, endAt, null, false, actor, now);
    }

    private void requireFrom(UUID actor, Instant now, AppointmentStatus... allowed) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        if (!List.of(allowed).contains(status)) {
            throw new InvalidStatusTransitionException();
        }
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 1000) {
            throw new InvalidAppointmentException("reason", "Reason must contain 1 to 1000 characters.");
        }
        return reason;
    }

    /** Normal changes need now <= startAt - 24 h; inside it, only an Administrator override applies. */
    private boolean window(boolean override, StaffRole role, Instant now) {
        if (override && role != StaffRole.ADMINISTRATOR) {
            throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
        }
        boolean inside = now.isAfter(startAt.toInstant().minus(Duration.ofHours(24)));
        if (inside && !override) {
            throw new AppointmentConflictException("The change is inside the 24-hour window.");
        }
        return inside;
    }

    private Transition move(LifecycleOperation operation, AppointmentStatus to, ConfirmationStatus confirmation,
                            ReminderStatus reminder, OffsetDateTime nextStart, OffsetDateTime nextEnd, String reason,
                            boolean override, UUID actor, Instant now) {
        Appointment next = new Appointment(new AppointmentSnapshot(id, patientId, dentistId, nextStart, nextEnd,
                this.reason, to, confirmation, reminder, version + 1, createdAt, createdBy, now, actor, typeCode));
        boolean moved = operation == LifecycleOperation.RESCHEDULE;
        return new Transition(next, new StatusChange(operation, status, to, moved ? startAt : null,
                moved ? endAt : null, moved ? nextStart : null, moved ? nextEnd : null, reason, override));
    }

    public AppointmentSnapshot snapshot() {
        return new AppointmentSnapshot(id, patientId, dentistId, startAt, endAt, reason, status,
                confirmationStatus, reminderStatus, version, createdAt, createdBy, updatedAt, updatedBy, typeCode);
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
    /** HU-APT-006 appointment type code, or null when none was chosen. */
    public String typeCode() { return typeCode; }
}
