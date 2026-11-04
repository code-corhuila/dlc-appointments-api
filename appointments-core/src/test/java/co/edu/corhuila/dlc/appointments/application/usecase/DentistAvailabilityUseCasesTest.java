package co.edu.corhuila.dlc.appointments.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.port.in.ReplaceAvailabilityCommand;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.DentistAvailabilityStore;
import co.edu.corhuila.dlc.appointments.domain.model.DentistAvailability;
import co.edu.corhuila.dlc.appointments.domain.model.DentistNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import co.edu.corhuila.dlc.appointments.domain.model.TimeInterval;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-APT-001 availability: schedulers replace it for an active Dentist; a Dentist reads only their own. */
class DentistAvailabilityUseCasesTest {
    private static final UUID DENTIST = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00Z");
    private static final TimeInterval MORNING = new TimeInterval(OffsetDateTime.parse("2031-02-03T08:00:00-05:00"),
            OffsetDateTime.parse("2031-02-03T12:00:00-05:00"));
    private Optional<DentistAvailability> stored = Optional.empty();
    private boolean dentistActive = true;

    private final DentistAvailabilityStore store = new DentistAvailabilityStore() {
        @Override
        public Optional<DentistAvailability> find(UUID dentistId) {
            return dentistId.equals(DENTIST) ? stored : Optional.empty();
        }

        @Override
        public DentistAvailability replace(UUID dentistId, Change change) {
            stored = Optional.of(change.apply(find(dentistId), List.of()));
            return stored.get();
        }
    };
    private final AppointmentEligibility eligibility = new AppointmentEligibility() {
        @Override
        public boolean isPatientActive(UUID patientId) {
            throw new AssertionError("not used");
        }

        @Override
        public boolean isDentistAuthorized(UUID dentistId) {
            return dentistActive && dentistId.equals(DENTIST);
        }
    };
    private final ReplaceDentistAvailabilityUseCase replace = new ReplaceDentistAvailabilityUseCase(store,
            eligibility, UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC));
    private final ReadDentistAvailabilityUseCase read = new ReadDentistAvailabilityUseCase(store, eligibility);

    @Test
    void schedulerReplacesAvailabilityOfAnActiveDentistAsTheActor() {
        UUID actor = UUID.randomUUID();
        DentistAvailability saved = replace.replace(command(DENTIST, 1), reader(actor, "SECRETARY_ASSISTANT"));
        assertEquals(1, saved.version());
        assertEquals(actor, saved.updatedBy());
        assertEquals(NOW, saved.updatedAt());
        assertSame(saved, read.read(DENTIST, reader(UUID.randomUUID(), "ADMINISTRATOR")));
    }

    @Test
    void onlySchedulersReplaceAvailability() {
        assertEquals(SchedulingException.Reason.AUTHORIZATION_DENIED, assertThrows(SchedulingException.class,
                () -> replace.replace(command(DENTIST, 1), reader(DENTIST, "DENTIST"))).reason());
    }

    @Test
    void staffThatIsNotAnActiveDentistIsNotFound() {
        assertThrows(DentistNotFoundException.class,
                () -> replace.replace(command(UUID.randomUUID(), 1), reader(UUID.randomUUID(), "ADMINISTRATOR")));
        dentistActive = false;
        assertThrows(DentistNotFoundException.class,
                () -> read.read(DENTIST, reader(UUID.randomUUID(), "ADMINISTRATOR")));
    }

    @Test
    void dentistReadsOnlyTheirOwnStoredAvailability() {
        assertThrows(DentistNotFoundException.class, () -> read.read(DENTIST, reader(DENTIST, "DENTIST")));
        replace.replace(command(DENTIST, 1), reader(UUID.randomUUID(), "ADMINISTRATOR"));
        assertEquals(1, read.read(DENTIST, reader(DENTIST, "DENTIST")).version());
        assertThrows(DentistNotFoundException.class, () -> read.read(DENTIST, reader(UUID.randomUUID(), "DENTIST")));
    }

    private static ReplaceAvailabilityCommand command(UUID dentist, long expectedVersion) {
        return new ReplaceAvailabilityCommand(dentist, List.of(MORNING), List.of(), expectedVersion);
    }

    private static ReaderContext reader(UUID actor, String role) {
        return new ReaderContext(actor, Set.of(role));
    }
}
