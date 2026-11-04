package co.edu.corhuila.dlc.appointments.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.dlc.appointments.application.model.AssignmentPage;
import co.edu.corhuila.dlc.appointments.application.model.AssignmentQuery;
import co.edu.corhuila.dlc.appointments.application.model.AssignmentResult;
import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.ClinicalAssignmentStore;
import co.edu.corhuila.dlc.appointments.domain.model.AssignmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignment;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** HU-APT-004 orchestration: staff only, owner eligibility before the write, metadata-only reads. */
class ClinicalAssignmentsUseCaseTest {
    private static final Instant NOW = Instant.parse("2031-05-01T12:00:00Z");
    private static final UUID GENERATED = UUID.fromString("00000000-0000-0000-0000-0000000000a9");
    private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private final FakeStore store = new FakeStore();
    private final FakeEligibility eligibility = new FakeEligibility();
    private final ClinicalAssignmentsUseCase useCase = new ClinicalAssignmentsUseCase(store, eligibility,
            () -> GENERATED, Clock.fixed(NOW, ZoneOffset.UTC));

    private static SchedulingContext as(String role) {
        return new SchedulingContext(STAFF, UUID.randomUUID(), "retry-key-0001", Set.of(role), UUID.randomUUID());
    }

    @Test
    void startCreatesTheAssignmentAfterBothOwnersConfirmEligibility() {
        UUID patient = UUID.randomUUID();
        UUID dentist = UUID.randomUUID();
        AssignmentResult result = useCase.start(patient, dentist, "Plan", as("SECRETARY_ASSISTANT"));

        assertTrue(result.created());
        assertEquals(GENERATED, result.assignment().id());
        assertEquals(STAFF, result.assignment().startedBy());
        assertEquals(NOW, result.assignment().startedAt());
        assertEquals(List.of(patient, dentist), eligibility.checked);
    }

    @Test
    void ineligibleOwnersAndTheDentistRoleAreRejectedBeforeAnyWrite() {
        eligibility.patientActive = false;
        assertEquals(SchedulingException.Reason.PATIENT_INELIGIBLE, assertThrows(SchedulingException.class,
                () -> useCase.start(UUID.randomUUID(), UUID.randomUUID(), "Plan", as("ADMINISTRATOR"))).reason());
        eligibility.patientActive = true;
        eligibility.dentistAuthorized = false;
        assertEquals(SchedulingException.Reason.DENTIST_INELIGIBLE, assertThrows(SchedulingException.class,
                () -> useCase.start(UUID.randomUUID(), UUID.randomUUID(), "Plan", as("ADMINISTRATOR"))).reason());
        assertEquals(SchedulingException.Reason.AUTHORIZATION_DENIED, assertThrows(SchedulingException.class,
                () -> useCase.start(UUID.randomUUID(), UUID.randomUUID(), "Plan", as("DENTIST"))).reason());
        assertEquals(0, store.writes);
    }

    @Test
    void aKnownKeyIsAnsweredFromTheStoreWithoutCheckingOwnersAgain() {
        var original = new AssignmentResult(ClinicalAssignment.start(GENERATED, UUID.randomUUID(), UUID.randomUUID(),
                "Plan", STAFF, NOW), false);
        store.recovered = original;
        assertSame(original, useCase.start(UUID.randomUUID(), UUID.randomUUID(), "Plan", as("ADMINISTRATOR")));
        assertEquals(List.of(), eligibility.checked);
    }

    @Test
    void endingAppliesTheDomainRuleOnTheStoredAssignment() {
        store.saved = ClinicalAssignment.start(GENERATED, UUID.randomUUID(), UUID.randomUUID(), "Plan", STAFF,
                NOW.minusSeconds(3600));
        ClinicalAssignment ended = useCase.end(GENERATED, 1, "Finished", as("ADMINISTRATOR"));
        assertEquals(AssignmentStatus.ENDED, ended.status());
        assertEquals(NOW, ended.endedAt());
        assertEquals(SchedulingException.Reason.AUTHORIZATION_DENIED, assertThrows(SchedulingException.class,
                () -> useCase.end(GENERATED, 2, "Again", as("DENTIST"))).reason());
    }

    @Test
    void readsAreForSchedulingStaffOnlyAndAMissingIdIsNotFound() {
        var staff = new ReaderContext(STAFF, Set.of("SECRETARY_ASSISTANT"));
        assertThrows(ClinicalAssignmentNotFoundException.class, () -> useCase.read(UUID.randomUUID(), staff));
        var query = new AssignmentQuery(1, 20, Optional.empty(), Optional.empty());
        assertEquals(0, useCase.list(query, staff).total());
        assertEquals(SchedulingException.Reason.AUTHORIZATION_DENIED, assertThrows(SchedulingException.class,
                () -> useCase.list(query, new ReaderContext(STAFF, Set.of("DENTIST")))).reason());
    }

    private static final class FakeStore implements ClinicalAssignmentStore {
        AssignmentResult recovered;
        ClinicalAssignment saved;
        int writes;

        @Override
        public Optional<AssignmentResult> recover(SchedulingContext context, ClinicalAssignment proposed) {
            return Optional.ofNullable(recovered);
        }

        @Override
        public AssignmentResult start(SchedulingContext context, ClinicalAssignment proposed) {
            writes++;
            saved = proposed;
            return new AssignmentResult(proposed, true);
        }

        @Override
        public ClinicalAssignment end(UUID id, String requestHashInput, SchedulingContext context,
                                      UnaryOperator<ClinicalAssignment> decide) {
            writes++;
            if (saved == null || !saved.id().equals(id)) throw new ClinicalAssignmentNotFoundException();
            saved = decide.apply(saved);
            return saved;
        }

        @Override
        public Optional<ClinicalAssignment> find(UUID id) {
            return Optional.ofNullable(saved).filter(a -> a.id().equals(id));
        }

        @Override
        public AssignmentPage search(AssignmentQuery query) {
            return new AssignmentPage(List.of(), query.page(), query.limit(), 0);
        }
    }

    private static final class FakeEligibility implements AppointmentEligibility {
        boolean patientActive = true;
        boolean dentistAuthorized = true;
        final java.util.ArrayList<UUID> checked = new java.util.ArrayList<>();

        @Override
        public boolean isPatientActive(UUID patientId) {
            checked.add(patientId);
            return patientActive;
        }

        @Override
        public boolean isDentistAuthorized(UUID dentistId) {
            checked.add(dentistId);
            return dentistAuthorized;
        }
    }
}
