package co.edu.corhuila.dlc.appointments.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.WaitingListEntry;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.WaitingListStore;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentConflictException;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidAppointmentException;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import co.edu.corhuila.dlc.appointments.domain.model.StaleVersionException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** HU-APT-005 (APT-013): waiting-list entries, staff only, one WAITING per patient and preferred dentist. */
class WaitingListUseCaseTest {
    private static final Instant NOW = Instant.parse("2026-11-09T15:00:00Z");
    private final UUID patient = UUID.randomUUID();
    private final UUID dentist = UUID.randomUUID();
    private final SchedulingContext secretary = new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(),
            "wait-key-0001", Set.of("SECRETARY_ASSISTANT"), UUID.randomUUID());
    private final Store store = new Store();
    private boolean patientActive = true;
    private final AppointmentEligibility eligibility = new AppointmentEligibility() {
        public boolean isPatientActive(UUID id) { return patientActive; }
        public boolean isDentistAuthorized(UUID id) { return true; }
    };
    private final WaitingListUseCase useCase = new WaitingListUseCase(store, eligibility, UUID::randomUUID,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void addsAnActivePatientAsWaitingWithTheServerActorAndTime() {
        var result = useCase.create(patient, dentist, "Limpieza", LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 20), secretary);
        assertTrue(result.created());
        var entry = result.entry();
        assertEquals(WaitingListEntry.Status.WAITING, entry.status());
        assertEquals(secretary.actorUserId(), entry.createdBy());
        assertEquals(NOW, entry.createdAt());
        assertEquals(1, entry.version());
    }

    @Test
    void rejectsDentistsInactivePatientsAndInvalidRequests() {
        var dentistContext = new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "wait-key-0002",
                Set.of("DENTIST"), UUID.randomUUID());
        assertEquals(SchedulingException.Reason.AUTHORIZATION_DENIED, assertThrows(SchedulingException.class,
                () -> useCase.create(patient, null, "x", null, null, dentistContext)).reason());
        assertThrows(SchedulingException.class,
                () -> useCase.list(WaitingListEntry.Status.WAITING, 1, 20, new ReaderContext(UUID.randomUUID(), Set.of("DENTIST"))));
        assertEquals("reason", assertThrows(InvalidAppointmentException.class,
                () -> useCase.create(patient, null, " ", null, null, secretary)).field());
        assertEquals("preferredTo", assertThrows(InvalidAppointmentException.class,
                () -> useCase.create(patient, null, "x", LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 10),
                        secretary)).field());
        patientActive = false;
        assertEquals(SchedulingException.Reason.PATIENT_INELIGIBLE, assertThrows(SchedulingException.class,
                () -> useCase.create(patient, null, "x", null, null, secretary)).reason());
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void resolvesAsScheduledOnlyWithAnAppointmentOfTheSamePatient() {
        var entry = useCase.create(patient, dentist, "Limpieza", null, null, secretary).entry();
        UUID other = UUID.randomUUID();
        store.appointmentPatients.put(other, UUID.randomUUID());
        assertEquals("appointmentId", assertThrows(InvalidAppointmentException.class,
                () -> useCase.resolve(entry.id(), WaitingListEntry.Status.SCHEDULED, other, 1, secretary)).field());
        assertEquals("appointmentId", assertThrows(InvalidAppointmentException.class,
                () -> useCase.resolve(entry.id(), WaitingListEntry.Status.SCHEDULED, null, 1, secretary)).field());

        UUID booked = UUID.randomUUID();
        store.appointmentPatients.put(booked, patient);
        var resolved = useCase.resolve(entry.id(), WaitingListEntry.Status.SCHEDULED, booked, 1, secretary);
        assertEquals(WaitingListEntry.Status.SCHEDULED, resolved.status());
        assertEquals(booked, resolved.appointmentId());
        assertEquals(2, resolved.version());
        assertEquals(NOW, resolved.resolvedAt());
    }

    @Test
    void resolvedEntriesAreImmutableAndStaleVersionsConflict() {
        var entry = useCase.create(patient, null, "Control", null, null, secretary).entry();
        assertThrows(StaleVersionException.class,
                () -> useCase.resolve(entry.id(), WaitingListEntry.Status.WITHDRAWN, null, 7, secretary));
        var withdrawn = useCase.resolve(entry.id(), WaitingListEntry.Status.WITHDRAWN, null, 1, secretary);
        assertEquals(WaitingListEntry.Status.WITHDRAWN, withdrawn.status());
        assertThrows(InvalidStatusTransitionException.class,
                () -> useCase.resolve(entry.id(), WaitingListEntry.Status.WITHDRAWN, null, 2, secretary));
        assertThrows(InvalidAppointmentException.class,
                () -> useCase.resolve(entry.id(), WaitingListEntry.Status.WAITING, null, 2, secretary));
    }

    @Test
    void aSecondWaitingEntryForTheSamePairIsAConflict() {
        useCase.create(patient, dentist, "Uno", null, null, secretary);
        store.duplicate = true;
        assertThrows(AppointmentConflictException.class,
                () -> useCase.create(patient, dentist, "Dos", null, null, secretary));
        assertFalse(store.saved.isEmpty());
    }

    private static final class Store implements WaitingListStore {
        final List<WaitingListEntry> saved = new ArrayList<>();
        final java.util.Map<UUID, UUID> appointmentPatients = new java.util.HashMap<>();
        boolean duplicate;

        public Optional<Result> recover(SchedulingContext context, String requestHash) { return Optional.empty(); }

        public Result create(SchedulingContext context, WaitingListEntry entry, String requestHash) {
            if (duplicate) throw new AppointmentConflictException("A waiting entry already exists.");
            saved.add(entry);
            return new Result(entry, true);
        }

        public WaitingListEntry resolve(UUID id, String requestHash, SchedulingContext context,
                UnaryOperator<WaitingListEntry> decide) {
            var current = saved.stream().filter(e -> e.id().equals(id)).reduce((a, b) -> b).orElseThrow();
            var next = decide.apply(current);
            saved.add(next);
            return next;
        }

        public Page list(WaitingListEntry.Status status, int page, int limit) {
            return new Page(saved.stream().filter(e -> e.status() == status).toList(), page, limit, saved.size());
        }

        public Optional<UUID> appointmentPatient(UUID appointmentId) {
            return Optional.ofNullable(appointmentPatients.get(appointmentId));
        }
    }
}
