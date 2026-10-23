package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointmentCommand;
import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointment;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentRegistration;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidAppointmentException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static co.edu.corhuila.dlc.appointments.domain.model.SchedulingException.Reason.*;

class ScheduleAppointmentUseCaseTest {
    private final FakePorts ports = new FakePorts();
    private final ScheduleAppointment useCase = new ScheduleAppointmentUseCase(ports, ports, ports);
    private final OffsetDateTime start = OffsetDateTime.parse("2026-11-01T09:00:00-05:00");
    private final ScheduleAppointmentCommand command = new ScheduleAppointmentCommand(
            UUID.randomUUID(), UUID.randomUUID(), start, start.plusMinutes(30), "Checkup");

    @Test
    void schedulesAndRegistersTheValidatedAppointment() {
        Appointment result = useCase.schedule(command);
        assertSame(result, ports.registered);
        assertNotNull(result.id());
        assertEquals(command.patientId(), result.patientId());
        assertEquals(command.dentistId(), result.dentistId());
        assertEquals(command.startAt(), result.startAt());
        assertEquals(command.endAt(), result.endAt());
        assertEquals(command.reason(), result.reason());
        assertEquals(AppointmentStatus.PROGRAMADA, result.status());
        assertEquals(1, ports.registrations);
        assertEquals(3, ports.checks);
    }

    @Test
    void returnsAuthoritativeRegistrationResultWithDifferentIdentifier() {
        ports.result = Appointment.schedule(UUID.randomUUID(), command.patientId(), command.dentistId(),
                command.startAt(), command.endAt(), command.reason());
        Appointment result = useCase.schedule(command);
        assertSame(ports.result, result);
        assertNotEquals(ports.registered.id(), result.id());
        assertEquals(1, ports.registrations);
    }

    @Test
    void requiresAllConstructorDependencies() {
        assertThrows(NullPointerException.class, () -> new ScheduleAppointmentUseCase(null, ports, ports));
        assertThrows(NullPointerException.class, () -> new ScheduleAppointmentUseCase(ports, null, ports));
        assertThrows(NullPointerException.class, () -> new ScheduleAppointmentUseCase(ports, ports, null));
    }

    @Test
    void invalidRequiredFieldsAndReasonsNeverReachExternalPorts() {
        var invalidCommands = new ScheduleAppointmentCommand[]{
            new ScheduleAppointmentCommand(null, command.dentistId(), start, command.endAt(), "Checkup"),
            new ScheduleAppointmentCommand(command.patientId(), null, start, command.endAt(), "Checkup"),
            new ScheduleAppointmentCommand(command.patientId(), command.dentistId(), null, command.endAt(), "Checkup"),
            new ScheduleAppointmentCommand(command.patientId(), command.dentistId(), start, null, "Checkup"),
            new ScheduleAppointmentCommand(command.patientId(), command.dentistId(), start, command.endAt(), null),
            new ScheduleAppointmentCommand(command.patientId(), command.dentistId(), start, command.endAt(), " "),
            new ScheduleAppointmentCommand(command.patientId(), command.dentistId(), start, command.endAt(), "x".repeat(501))
        };
        for (var invalid : invalidCommands) {
            assertThrows(InvalidAppointmentException.class, () -> useCase.schedule(invalid));
            assertEquals(0, ports.checks);
            assertEquals(0, ports.registrations);
        }
    }

    @Test
    void apt001RejectsInactivePatientWithoutRegistration() {
        ports.patientActive = false;
        assertRejected(PATIENT_INELIGIBLE);
        assertEquals(1, ports.checks);
    }

    @Test
    void apt001RejectsInactiveOrUnauthorizedDentistWithoutRegistration() {
        ports.dentistAuthorized = false;
        assertRejected(DENTIST_INELIGIBLE);
        assertEquals(2, ports.checks);
    }

    @Test
    void apt002RejectsUnavailableIntervalWithoutRegistration() {
        ports.available = false;
        assertRejected(SLOT_UNAVAILABLE);
        assertEquals(3, ports.checks);
    }

    @Test
    void invalidIntervalFailsBeforeAnyDependencyCall() {
        var invalid = new ScheduleAppointmentCommand(command.patientId(), command.dentistId(),
                start, start, command.reason());
        var error = assertThrows(InvalidAppointmentException.class, () -> useCase.schedule(invalid));
        assertEquals("endAt", error.field());
        assertEquals(0, ports.checks);
        assertEquals(0, ports.registrations);
    }

    @Test
    void dependencyFailuresPropagateAndNeverAuthorizeRegistration() {
        for (int stage = 1; stage <= 3; stage++) {
            ports.checks = 0;
            ports.failAt = stage;
            ports.failure = new SchedulingException(DEPENDENCY_UNAVAILABLE);
            assertSame(ports.failure, assertThrows(SchedulingException.class,
                    () -> useCase.schedule(command)));
            assertEquals(stage, ports.checks);
            assertEquals(0, ports.registrations);
        }
    }

    @Test
    void registrationConflictPropagatesWithoutRetryOrSuccess() {
        ports.failure = new SchedulingException(SLOT_UNAVAILABLE);
        ports.failRegistration = true;
        assertSame(ports.failure, assertThrows(SchedulingException.class, () -> useCase.schedule(command)));
        assertEquals(1, ports.registrations);
        assertNull(ports.registered);
    }

    private void assertRejected(SchedulingException.Reason reason) {
        assertEquals(reason, assertThrows(SchedulingException.class,
                () -> useCase.schedule(command)).reason());
        assertEquals(0, ports.registrations);
        assertNull(ports.registered);
    }

    private final class FakePorts implements AppointmentEligibility, AppointmentAvailability,
            AppointmentRegistration {
        boolean patientActive = true, dentistAuthorized = true, available = true;
        boolean failRegistration;
        int checks, registrations, failAt;
        SchedulingException failure;
        Appointment registered;
        Appointment result;

        private void check() {
            if (++checks == failAt) throw failure;
        }

        public boolean isPatientActive(UUID patientId) {
            assertEquals(command.patientId(), patientId);
            check();
            return patientActive;
        }

        public boolean isDentistAuthorized(UUID dentistId) {
            assertEquals(command.dentistId(), dentistId);
            check();
            return dentistAuthorized;
        }

        public boolean isAvailable(UUID dentistId, OffsetDateTime from, OffsetDateTime to) {
            assertEquals(command.dentistId(), dentistId);
            assertEquals(command.startAt(), from);
            assertEquals(command.endAt(), to);
            check();
            return available;
        }

        public Appointment register(Appointment appointment) {
            registrations++;
            if (failRegistration) throw failure;
            registered = appointment;
            return result == null ? appointment : result;
        }
    }
}
