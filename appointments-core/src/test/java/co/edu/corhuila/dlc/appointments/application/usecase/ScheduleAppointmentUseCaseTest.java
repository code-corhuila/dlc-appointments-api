package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointmentCommand;
import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointment;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentRegistration;
import co.edu.corhuila.dlc.appointments.application.port.out.SchedulingAuthorization;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingResult;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidAppointmentException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static co.edu.corhuila.dlc.appointments.domain.model.SchedulingException.Reason.*;

class ScheduleAppointmentUseCaseTest {
    private final SchedulingContext context = new SchedulingContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final FakePorts ports = new FakePorts();
    private final ScheduleAppointment useCase = new ScheduleAppointmentUseCase(ports, ports, ports, ports);
    private final OffsetDateTime start = OffsetDateTime.parse("2026-11-01T09:00:00-05:00");
    private final ScheduleAppointmentCommand command = new ScheduleAppointmentCommand(
            UUID.randomUUID(), UUID.randomUUID(), start, start.plusMinutes(30), "Checkup");

    @Test
    void schedulesAndRegistersTheValidatedAppointment() {
        Appointment result = schedule(command);
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
        assertEquals(1, ports.authorizations);
        assertEquals(1, ports.recoveries);
    }

    @Test
    void returnsAuthoritativeRegistrationResultWithDifferentIdentifier() {
        ports.result = new SchedulingResult(Appointment.schedule(UUID.randomUUID(), command.patientId(),
                command.dentistId(), command.startAt(), command.endAt(), command.reason()), 7);
        SchedulingResult result = useCase.schedule(command, context);
        assertSame(ports.result, result);
        assertEquals(7, result.version());
        assertNotEquals(ports.registered.id(), result.appointment().id());
        assertEquals(1, ports.registrations);
    }

    @Test
    void requiresAllConstructorDependencies() {
        assertThrows(NullPointerException.class, () -> new ScheduleAppointmentUseCase(null, ports, ports, ports));
        assertThrows(NullPointerException.class, () -> new ScheduleAppointmentUseCase(ports, null, ports, ports));
        assertThrows(NullPointerException.class, () -> new ScheduleAppointmentUseCase(ports, ports, null, ports));
        assertThrows(NullPointerException.class, () -> new ScheduleAppointmentUseCase(ports, ports, ports, null));
    }

    @Test
    void nullContextFailsBeforeAuthorizationOrRecovery() {
        assertThrows(NullPointerException.class, () -> useCase.schedule(command, null));
        assertEquals(0, ports.authorizations);
        assertEquals(0, ports.recoveries);
        assertEquals(0, ports.registrations);
    }

    @Test
    void authorizationRejectionAndFailureNeverReachRecoveryOrRegistration() {
        for (var reason : new SchedulingException.Reason[]{AUTHORIZATION_DENIED, DEPENDENCY_UNAVAILABLE}) {
            ports.authorizationFailure = new SchedulingException(reason);
            assertSame(ports.authorizationFailure, assertThrows(SchedulingException.class, () -> schedule(command)));
            assertEquals(0, ports.recoveries);
            assertEquals(0, ports.checks);
            assertEquals(0, ports.registrations);
        }
        assertEquals(2, ports.authorizations);
    }

    @Test
    void recoversOriginalResultAndVersionBeforeEligibilityOrAvailabilityOnEveryAttempt() {
        ports.recovered = new SchedulingResult(Appointment.schedule(UUID.randomUUID(), command.patientId(),
                command.dentistId(), start, command.endAt(), command.reason()), 7);
        ports.available = false;
        ports.patientActive = false;
        for (int attempt = 0; attempt < 2; attempt++) {
            assertSame(ports.recovered, useCase.schedule(command, context));
        }
        assertEquals(2, ports.authorizations);
        assertEquals(2, ports.recoveries);
        assertEquals(0, ports.checks);
        assertEquals(0, ports.registrations);
        ports.authorizationFailure = new SchedulingException(AUTHORIZATION_DENIED);
        assertSame(ports.authorizationFailure, assertThrows(SchedulingException.class, () -> schedule(command)));
        assertEquals(3, ports.authorizations);
        assertEquals(2, ports.recoveries);
    }

    @Test
    void recoveryConflictAndFailureNeverBecomeAnAbsentOperation() {
        for (var reason : new SchedulingException.Reason[]{IDEMPOTENCY_CONFLICT, DEPENDENCY_UNAVAILABLE}) {
            ports.recoveryFailure = new SchedulingException(reason);
            assertSame(ports.recoveryFailure, assertThrows(SchedulingException.class, () -> schedule(command)));
            assertEquals(0, ports.checks);
            assertEquals(0, ports.registrations);
        }
    }

    private Appointment schedule(ScheduleAppointmentCommand request) {
        return useCase.schedule(request, context).appointment();
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
            assertThrows(InvalidAppointmentException.class, () -> schedule(invalid));
            assertEquals(0, ports.checks);
            assertEquals(0, ports.registrations);
            assertEquals(0, ports.recoveries);
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
        var error = assertThrows(InvalidAppointmentException.class, () -> schedule(invalid));
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
                    () -> schedule(command)));
            assertEquals(stage, ports.checks);
            assertEquals(0, ports.registrations);
        }
    }

    @Test
    void registrationConflictPropagatesWithoutRetryOrSuccess() {
        ports.failure = new SchedulingException(SLOT_UNAVAILABLE);
        ports.failRegistration = true;
        assertSame(ports.failure, assertThrows(SchedulingException.class, () -> schedule(command)));
        assertEquals(1, ports.registrations);
        assertNull(ports.registered);
    }

    private void assertRejected(SchedulingException.Reason reason) {
        assertEquals(reason, assertThrows(SchedulingException.class,
                () -> schedule(command)).reason());
        assertEquals(0, ports.registrations);
        assertNull(ports.registered);
    }

    private final class FakePorts implements AppointmentEligibility, AppointmentAvailability,
            AppointmentRegistration, SchedulingAuthorization {
        boolean patientActive = true, dentistAuthorized = true, available = true;
        boolean failRegistration;
        int checks, registrations, failAt;
        SchedulingException failure;
        Appointment registered;
        SchedulingResult result;
        int authorizations, recoveries;
        SchedulingException authorizationFailure, recoveryFailure;
        SchedulingResult recovered;

        public void requireSchedulingAllowed(UUID actorUserId, UUID sessionId) {
            authorizations++;
            assertEquals(context.actorUserId(), actorUserId);
            assertEquals(context.sessionId(), sessionId);
            if (authorizationFailure != null) throw authorizationFailure;
        }

        public Optional<SchedulingResult> recover(SchedulingContext operation, Appointment proposed) {
            assertSame(context, operation);
            assertEquals(command.patientId(), proposed.patientId());
            assertEquals(command.dentistId(), proposed.dentistId());
            assertEquals(command.startAt().toInstant(), proposed.startAt().toInstant());
            assertEquals(command.endAt().toInstant(), proposed.endAt().toInstant());
            assertEquals(command.reason(), proposed.reason());
            assertTrue(authorizations > recoveries);
            recoveries++;
            if (recoveryFailure != null) throw recoveryFailure;
            return Optional.ofNullable(recovered);
        }

        private void check() {
            assertEquals(authorizations, recoveries);
            assertTrue(recoveries > 0);
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

        public SchedulingResult register(SchedulingContext operation, Appointment appointment) {
            assertSame(context, operation);
            registrations++;
            if (failRegistration) throw failure;
            registered = appointment;
            return result == null ? new SchedulingResult(appointment, 1) : result;
        }
    }
}
