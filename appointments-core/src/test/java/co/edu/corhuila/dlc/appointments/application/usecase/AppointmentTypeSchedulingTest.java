package co.edu.corhuila.dlc.appointments.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingResult;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentRegistration;
import co.edu.corhuila.dlc.appointments.application.port.out.SchedulingAuthorization;
import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointmentCommand;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-APT-006 (APT-014): an appointment may name one active catalog type; it survives transitions. */
class AppointmentTypeSchedulingTest {
    private static final Instant NOW = Instant.parse("2026-10-30T15:00:00Z");
    private final Ports ports = new Ports();
    private final SchedulingContext context = new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(),
            "retry-key-0002", Set.of("SECRETARY_ASSISTANT"), UUID.randomUUID());
    private final ScheduleAppointmentUseCase useCase = new ScheduleAppointmentUseCase(ports, ports, ports, ports,
            UUID::randomUUID, Clock.fixed(NOW, ZoneOffset.UTC), code -> Set.of("LIMPIEZA").contains(code));
    private final OffsetDateTime start = OffsetDateTime.parse("2026-11-01T09:00:00-05:00");

    private ScheduleAppointmentCommand command(String typeCode) {
        return new ScheduleAppointmentCommand(UUID.randomUUID(), UUID.randomUUID(), start, start.plusMinutes(30),
                "Limpieza semestral", typeCode);
    }

    @Test
    void keepsAnActiveTypeOnTheScheduledAppointment() {
        Appointment scheduled = useCase.schedule(command("LIMPIEZA"), context).appointment();
        assertEquals("LIMPIEZA", scheduled.typeCode());
        assertEquals("LIMPIEZA", scheduled.snapshot().typeCode());
    }

    @Test
    void schedulesWithoutATypeWhenNoneIsChosen() {
        assertNull(useCase.schedule(command(null), context).appointment().typeCode());
        assertNull(useCase.schedule(new ScheduleAppointmentCommand(UUID.randomUUID(), UUID.randomUUID(), start,
                start.plusMinutes(30), "Sin tipo"), context).appointment().typeCode());
    }

    @Test
    void rejectsAnUnknownOrInactiveType() {
        var failure = assertThrows(SchedulingException.class, () -> useCase.schedule(command("BLANQUEAMIENTO"), context));
        assertEquals(SchedulingException.Reason.TYPE_UNAVAILABLE, failure.reason());
    }

    @Test
    void theTypeSurvivesLifecycleTransitions() {
        Appointment scheduled = Appointment.schedule(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), start,
                start.plusMinutes(30), "Control", UUID.randomUUID(), NOW, "CONTROL");
        Appointment restored = Appointment.reconstitute(scheduled.snapshot());
        assertEquals("CONTROL", restored.typeCode());
    }

    /** Every owner check passes; registration runs the validation and returns the proposed appointment. */
    private static final class Ports implements AppointmentEligibility, AppointmentAvailability,
            AppointmentRegistration, SchedulingAuthorization {
        public void requireSchedulingAllowed(SchedulingContext operation) { }
        public Optional<SchedulingResult> recover(SchedulingContext operation, Appointment proposed) {
            return Optional.empty();
        }
        public boolean isPatientActive(UUID patientId) { return true; }
        public boolean isDentistAuthorized(UUID dentistId) { return true; }
        public boolean isAvailable(UUID dentistId, OffsetDateTime from, OffsetDateTime to) { return true; }
        public SchedulingResult register(SchedulingContext operation, Appointment appointment, Runnable validate) {
            validate.run();
            return new SchedulingResult(appointment, appointment.version(), true);
        }
    }
}
