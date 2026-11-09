package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.WaitingListEntry;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.IdGenerator;
import co.edu.corhuila.dlc.appointments.application.port.out.WaitingListStore;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidAppointmentException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** HU-APT-005: only Administrator and Secretary/Assistant read, add and resolve waiting-list entries. */
public final class WaitingListUseCase {
    private static final Set<String> STAFF = Set.of("ADMINISTRATOR", "SECRETARY_ASSISTANT");
    private final WaitingListStore store;
    private final AppointmentEligibility eligibility;
    private final IdGenerator ids;
    private final Clock clock;

    public WaitingListUseCase(WaitingListStore store, AppointmentEligibility eligibility, IdGenerator ids,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A known key is answered first; otherwise Patients (and Auth for a preferred dentist) confirm first. */
    public WaitingListStore.Result create(UUID patientId, UUID dentistId, String reason, LocalDate preferredFrom,
            LocalDate preferredTo, SchedulingContext context) {
        requireStaff(context.roles());
        var proposed = WaitingListEntry.waiting(ids.newId(), patientId, dentistId, reason, preferredFrom,
                preferredTo, context.actorUserId(), clock.instant());
        String hash = String.join("\n", String.valueOf(patientId), String.valueOf(dentistId), reason,
                String.valueOf(preferredFrom), String.valueOf(preferredTo));
        var recovered = store.recover(context, hash);
        if (recovered.isPresent()) return recovered.get();
        if (!eligibility.isPatientActive(patientId)) {
            throw new SchedulingException(SchedulingException.Reason.PATIENT_INELIGIBLE);
        }
        if (dentistId != null && !eligibility.isDentistAuthorized(dentistId)) {
            throw new SchedulingException(SchedulingException.Reason.DENTIST_INELIGIBLE);
        }
        return store.create(context, proposed, hash);
    }

    public WaitingListEntry resolve(UUID id, WaitingListEntry.Status outcome, UUID appointmentId,
            long expectedVersion, SchedulingContext context) {
        requireStaff(context.roles());
        String hash = String.join("\n", String.valueOf(id), String.valueOf(outcome), String.valueOf(appointmentId),
                String.valueOf(expectedVersion));
        return store.resolve(id, hash, context, current -> {
            if (appointmentId != null && !store.appointmentPatient(appointmentId)
                    .filter(current.patientId()::equals).isPresent()) {
                throw new InvalidAppointmentException("appointmentId",
                        "The appointment must exist and belong to the same patient.");
            }
            return current.resolve(outcome, appointmentId, expectedVersion, context.actorUserId(), clock.instant());
        });
    }

    public WaitingListStore.Page list(WaitingListEntry.Status status, int page, int limit, ReaderContext reader) {
        requireStaff(reader.roles());
        return store.list(status, page, limit);
    }

    private static void requireStaff(Set<String> roles) {
        if (roles.stream().noneMatch(STAFF::contains)) {
            throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
        }
    }
}
