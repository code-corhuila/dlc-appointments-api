package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.WaitingListEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** HU-APT-005 persistence: each write commits the entry and its Idempotency-Key together. */
public interface WaitingListStore {
    /** The entry an earlier request with the same key and payload created, if any. */
    Optional<Result> recover(SchedulingContext context, String requestHash);

    /** A second WAITING entry for the patient and preferred dentist is an AppointmentConflictException. */
    Result create(SchedulingContext context, WaitingListEntry entry, String requestHash);

    /** Locks the entry and stores what {@code decide} returns. */
    WaitingListEntry resolve(UUID id, String requestHash, SchedulingContext context,
            UnaryOperator<WaitingListEntry> decide);

    /** Oldest first: createdAt ascending, then id. */
    Page list(WaitingListEntry.Status status, int page, int limit);

    /** The patient of an existing, non-cancelled appointment. */
    Optional<UUID> appointmentPatient(UUID appointmentId);

    record Result(WaitingListEntry entry, boolean created) { }

    record Page(List<WaitingListEntry> data, int page, int limit, long total) { }
}
