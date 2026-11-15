package co.edu.corhuila.dlc.appointments.application.usecase;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentNotFoundException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-APT-003: staff schedulers read any appointment; a Dentist reads only their own (404 otherwise). */
class ReadAppointmentUseCaseTest {
    private static final UUID DENTIST = UUID.randomUUID();
    private static final OffsetDateTime START = OffsetDateTime.parse("2031-01-01T09:00:00-05:00");
    private final Appointment stored = Appointment.schedule(UUID.randomUUID(), UUID.randomUUID(), DENTIST, START,
            START.plusMinutes(30), "Checkup", UUID.randomUUID(), Instant.parse("2030-12-01T00:00:00Z"));
    private final ReadAppointmentUseCase useCase = new ReadAppointmentUseCase(
            id -> id.equals(stored.id()) ? Optional.of(stored) : Optional.empty());

    @Test
    void administratorAndSecretaryReadAnyAppointment() {
        assertSame(stored, useCase.read(stored.id(), reader(UUID.randomUUID(), "ADMINISTRATOR")));
        assertSame(stored, useCase.read(stored.id(), reader(UUID.randomUUID(), "SECRETARY_ASSISTANT")));
    }

    @Test
    void dentistReadsOnlyTheirOwnAppointment() {
        assertSame(stored, useCase.read(stored.id(), reader(DENTIST, "DENTIST")));
        assertThrows(AppointmentNotFoundException.class,
                () -> useCase.read(stored.id(), reader(UUID.randomUUID(), "DENTIST")));
    }

    @Test
    void unknownAppointmentOrUnknownRoleIsNotFound() {
        assertThrows(AppointmentNotFoundException.class,
                () -> useCase.read(UUID.randomUUID(), reader(UUID.randomUUID(), "ADMINISTRATOR")));
        assertThrows(AppointmentNotFoundException.class,
                () -> useCase.read(stored.id(), reader(UUID.randomUUID(), "PATIENT")));
    }

    private static ReaderContext reader(UUID actor, String role) {
        return new ReaderContext(actor, Set.of(role));
    }
}
