package co.edu.corhuila.dlc.appointments.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * HU-APT-004 ongoing assignment (REL-001/002): metadata only, never clinical content. Staff start it for an
 * eligible patient/Dentist pair, nobody assigns themselves, and ending it is audited and happens once.
 */
public final class ClinicalAssignment {
    private final UUID id;
    private final UUID patientId;
    private final UUID dentistId;
    private final AssignmentStatus status;
    private final String reason;
    private final Instant startedAt;
    private final UUID startedBy;
    private final Instant endedAt;
    private final UUID endedBy;
    private final String endReason;
    private final long version;

    private ClinicalAssignment(UUID id, UUID patientId, UUID dentistId, AssignmentStatus status, String reason,
            Instant startedAt, UUID startedBy, Instant endedAt, UUID endedBy, String endReason, long version) {
        this.id = id;
        this.patientId = patientId;
        this.dentistId = dentistId;
        this.status = status;
        this.reason = reason;
        this.startedAt = startedAt;
        this.startedBy = startedBy;
        this.endedAt = endedAt;
        this.endedBy = endedBy;
        this.endReason = endReason;
        this.version = version;
    }

    public static ClinicalAssignment start(UUID id, UUID patientId, UUID dentistId, String reason, UUID actor,
                                           Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(patientId, "patientId");
        Objects.requireNonNull(dentistId, "dentistId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        if (actor.equals(dentistId)) {
            throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
        }
        return new ClinicalAssignment(id, patientId, dentistId, AssignmentStatus.ACTIVE, reason(reason), now, actor,
                null, null, null, 1);
    }

    /** Restores stored state; the database checks already guarantee the ACTIVE/ENDED invariants. */
    public static ClinicalAssignment reconstitute(UUID id, UUID patientId, UUID dentistId, AssignmentStatus status,
            String reason, Instant startedAt, UUID startedBy, Instant endedAt, UUID endedBy, String endReason,
            long version) {
        if (id == null || patientId == null || dentistId == null || status == null || reason == null
                || startedAt == null || startedBy == null || version < 1) {
            throw new IllegalStateException("Stored assignment state is invalid.");
        }
        return new ClinicalAssignment(id, patientId, dentistId, status, reason, startedAt, startedBy, endedAt,
                endedBy, endReason, version);
    }

    /** ACTIVE → ENDED with the caller's version; an ENDED assignment answers INVALID_STATUS_TRANSITION. */
    public ClinicalAssignment end(String reason, long expectedVersion, UUID actor, Instant now) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        if (status == AssignmentStatus.ENDED) {
            throw new InvalidStatusTransitionException();
        }
        if (expectedVersion != version) {
            throw new StaleVersionException();
        }
        return new ClinicalAssignment(id, patientId, dentistId, AssignmentStatus.ENDED, this.reason, startedAt,
                startedBy, now, actor, reason(reason), version + 1);
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 1000) {
            throw new InvalidAppointmentException("reason", "Reason must contain 1 to 1000 characters.");
        }
        return reason;
    }

    public UUID id() { return id; }
    public UUID patientId() { return patientId; }
    public UUID dentistId() { return dentistId; }
    public AssignmentStatus status() { return status; }
    public String reason() { return reason; }
    public Instant startedAt() { return startedAt; }
    public UUID startedBy() { return startedBy; }
    public Instant endedAt() { return endedAt; }
    public UUID endedBy() { return endedBy; }
    public String endReason() { return endReason; }
    public long version() { return version; }
}
