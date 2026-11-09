package co.edu.corhuila.dlc.appointments.application.model;

import co.edu.corhuila.dlc.appointments.domain.model.InvalidAppointmentException;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.dlc.appointments.domain.model.StaleVersionException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** HU-APT-005 (APT-013): administrative waiting-list metadata, never clinical narrative. */
public record WaitingListEntry(UUID id, UUID patientId, UUID dentistId, String reason, LocalDate preferredFrom,
        LocalDate preferredTo, Status status, UUID appointmentId, Instant createdAt, UUID createdBy,
        Instant resolvedAt, UUID resolvedBy, long version) {

    public enum Status { WAITING, SCHEDULED, WITHDRAWN }

    public static WaitingListEntry waiting(UUID id, UUID patientId, UUID dentistId, String reason,
            LocalDate preferredFrom, LocalDate preferredTo, UUID actor, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        if (patientId == null) throw new InvalidAppointmentException("patientId", "Required value is missing.");
        if (reason == null || reason.isBlank() || reason.codePointCount(0, reason.length()) > 500) {
            throw new InvalidAppointmentException("reason", "Reason must contain 1 to 500 characters.");
        }
        if (preferredFrom != null && preferredTo != null && preferredFrom.isAfter(preferredTo)) {
            throw new InvalidAppointmentException("preferredTo", "The preferred window must not be reversed.");
        }
        return new WaitingListEntry(id, patientId, dentistId, reason, preferredFrom, preferredTo, Status.WAITING,
                null, now, actor, null, null, 1);
    }

    /** SCHEDULED needs the appointment; WITHDRAWN takes none. A resolved entry never changes again. */
    public WaitingListEntry resolve(Status outcome, UUID appointment, long expectedVersion, UUID actor, Instant now) {
        if (outcome == null || outcome == Status.WAITING) {
            throw new InvalidAppointmentException("outcome", "Outcome must be SCHEDULED or WITHDRAWN.");
        }
        if ((outcome == Status.SCHEDULED) != (appointment != null)) {
            throw new InvalidAppointmentException("appointmentId",
                    "SCHEDULED requires an appointment and WITHDRAWN takes none.");
        }
        if (status != Status.WAITING) throw new InvalidStatusTransitionException();
        if (expectedVersion != version) throw new StaleVersionException();
        return new WaitingListEntry(id, patientId, dentistId, reason, preferredFrom, preferredTo, outcome,
                appointment, createdAt, createdBy, now, actor, version + 1);
    }
}
