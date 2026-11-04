package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.model.AssignmentPage;
import co.edu.corhuila.dlc.appointments.application.model.AssignmentQuery;
import co.edu.corhuila.dlc.appointments.application.model.AssignmentResult;
import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.ClinicalAssignmentStore;
import co.edu.corhuila.dlc.appointments.application.port.out.IdGenerator;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignment;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** HU-APT-004: only Administrator and Secretary/Assistant start, end and read assignment metadata. */
public final class ClinicalAssignmentsUseCase {
    private static final Set<String> STAFF = Set.of("ADMINISTRATOR", "SECRETARY_ASSISTANT");
    private final ClinicalAssignmentStore store;
    private final AppointmentEligibility eligibility;
    private final IdGenerator ids;
    private final Clock clock;

    public ClinicalAssignmentsUseCase(ClinicalAssignmentStore store, AppointmentEligibility eligibility,
            IdGenerator ids, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** A known key is answered first; otherwise Patients and Auth confirm eligibility before the write. */
    public AssignmentResult start(UUID patientId, UUID dentistId, String reason, SchedulingContext context) {
        requireStaff(context.roles());
        ClinicalAssignment proposed = ClinicalAssignment.start(ids.newId(), patientId, dentistId, reason,
                context.actorUserId(), clock.instant());
        var recovered = store.recover(context, proposed);
        if (recovered.isPresent()) return recovered.get();
        if (!eligibility.isPatientActive(patientId)) {
            throw new SchedulingException(SchedulingException.Reason.PATIENT_INELIGIBLE);
        }
        if (!eligibility.isDentistAuthorized(dentistId)) {
            throw new SchedulingException(SchedulingException.Reason.DENTIST_INELIGIBLE);
        }
        return store.start(context, proposed);
    }

    /** The request hash covers the target, the version and the reason (Norma 5.3.8). */
    public ClinicalAssignment end(UUID id, long expectedVersion, String reason, SchedulingContext context) {
        requireStaff(context.roles());
        return store.end(id, id + "\n" + expectedVersion + "\n" + reason, context,
                current -> current.end(reason, expectedVersion, context.actorUserId(), clock.instant()));
    }

    public ClinicalAssignment read(UUID id, ReaderContext reader) {
        requireStaff(reader.roles());
        return store.find(id).orElseThrow(ClinicalAssignmentNotFoundException::new);
    }

    public AssignmentPage list(AssignmentQuery query, ReaderContext reader) {
        requireStaff(reader.roles());
        return store.search(query);
    }

    private static void requireStaff(Set<String> roles) {
        if (roles.stream().noneMatch(STAFF::contains)) {
            throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
        }
    }
}
