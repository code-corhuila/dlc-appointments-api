package co.edu.corhuila.dlc.appointments.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentLifecycleStore;
import co.edu.corhuila.dlc.appointments.application.port.out.SchedulingAuthorization;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentSnapshot;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.ConfirmationChannel;
import co.edu.corhuila.dlc.appointments.domain.model.ConfirmationStatus;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidStatusTransitionException;
import co.edu.corhuila.dlc.appointments.domain.model.ReminderStatus;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import co.edu.corhuila.dlc.appointments.domain.model.StaleVersionException;
import co.edu.corhuila.dlc.appointments.domain.model.StatusChange;
import co.edu.corhuila.dlc.appointments.domain.model.Transition;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** HU-APT-002 orchestration over the lifecycle store, with the D4 repeated cancellation. */
class ChangeAppointmentStatusUseCaseTest {
    private static final Instant NOW = Instant.parse("2031-05-01T12:00:00Z");
    private static final OffsetDateTime START = OffsetDateTime.parse("2031-05-10T09:00:00-05:00");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final SchedulingAuthorization STAFF_ONLY = context -> {
        if (!context.roles().contains("ADMINISTRATOR") && !context.roles().contains("SECRETARY_ASSISTANT")) {
            throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
        }
    };
    private final FakeStore store = new FakeStore();
    private final FakeEligibility eligibility = new FakeEligibility();
    private final ChangeAppointmentStatusUseCase useCase = new ChangeAppointmentStatusUseCase(store,
            id -> Optional.ofNullable(store.current).filter(a -> a.id().equals(id)), eligibility, eligibility,
            STAFF_ONLY, Clock.fixed(NOW, ZoneOffset.UTC));

    private static SchedulingContext as(String... roles) {
        return new SchedulingContext(UUID.fromString("00000000-0000-0000-0000-0000000000c9"), UUID.randomUUID(),
                "retry-key-0001", Set.of(roles), UUID.randomUUID());
    }

    private static Appointment in(AppointmentStatus status, long version) {
        Instant created = Instant.parse("2031-04-01T12:00:00Z");
        return Appointment.reconstitute(new AppointmentSnapshot(ID, UUID.randomUUID(), UUID.randomUUID(), START,
                START.plusMinutes(30), "Checkup", status, ConfirmationStatus.PENDING, ReminderStatus.PENDING, version,
                created, UUID.randomUUID(), created, UUID.randomUUID()));
    }

    @Test
    void confirmationAppliesTheDomainTransitionAsTheVerifiedActorAtServerTime() {
        store.current = in(AppointmentStatus.PROGRAMADA, 2);
        Appointment result = useCase.change(new LifecycleCommand.Confirm(ID, 2, ConfirmationChannel.PHONE),
                as("SECRETARY_ASSISTANT"));

        assertEquals(AppointmentStatus.CONFIRMADA, result.status());
        assertEquals(3, result.version());
        assertEquals(NOW, result.updatedAt());
        assertEquals(UUID.fromString("00000000-0000-0000-0000-0000000000c9"), result.updatedBy());
        assertSame(result, store.current);
    }

    @Test
    void staleVersionIsRejectedBeforeTheStateMachineAndDoubleConfirmationIsATransitionError() {
        store.current = in(AppointmentStatus.PROGRAMADA, 2);
        assertThrows(StaleVersionException.class, () -> useCase.change(
                new LifecycleCommand.Confirm(ID, 1, ConfirmationChannel.PHONE), as("ADMINISTRATOR")));
        store.current = in(AppointmentStatus.CONFIRMADA, 3);
        assertThrows(InvalidStatusTransitionException.class, () -> useCase.change(
                new LifecycleCommand.Confirm(ID, 3, ConfirmationChannel.PHONE), as("ADMINISTRATOR")));
    }

    @Test
    void cancellingACancelledAppointmentReturnsItUnchangedWhateverVersionIsSent() {
        store.current = in(AppointmentStatus.CANCELADA, 4);
        Appointment result = useCase.change(new LifecycleCommand.Cancel(ID, 1, "Again", false),
                as("SECRETARY_ASSISTANT"));

        assertSame(store.current, result);
        assertEquals(4, result.version());
        assertNull(store.lastChange);
    }

    @Test
    void overrideIsAuditedForTheAdministratorRole() {
        store.current = in(AppointmentStatus.PROGRAMADA, 1);
        Instant late = START.toInstant().minusSeconds(3600);
        var lateUseCase = new ChangeAppointmentStatusUseCase(store, id -> Optional.of(store.current), eligibility,
                eligibility,
                STAFF_ONLY, Clock.fixed(late, ZoneOffset.UTC));

        lateUseCase.change(new LifecycleCommand.Cancel(ID, 1, "Emergency", true), as("ADMINISTRATOR",
                "SECRETARY_ASSISTANT"));
        assertTrue(store.lastChange.override());
        store.current = in(AppointmentStatus.PROGRAMADA, 1);
        assertEquals(SchedulingException.Reason.AUTHORIZATION_DENIED, assertThrows(SchedulingException.class,
                () -> lateUseCase.change(new LifecycleCommand.Cancel(ID, 1, "Emergency", true),
                        as("SECRETARY_ASSISTANT"))).reason());
    }

    @Test
    void dentistCannotChangeStateAndNothingReachesTheStore() {
        store.current = in(AppointmentStatus.PROGRAMADA, 1);
        assertEquals(SchedulingException.Reason.AUTHORIZATION_DENIED, assertThrows(SchedulingException.class,
                () -> useCase.change(new LifecycleCommand.Confirm(ID, 1, ConfirmationChannel.EMAIL), as("DENTIST")))
                .reason());
        assertEquals(0, store.calls);
    }

    @Test
    void attentionStartRequiresAnActivePatientAndAnExistingAppointment() {
        store.current = in(AppointmentStatus.CONFIRMADA, 2);
        eligibility.active = false;
        assertEquals(SchedulingException.Reason.PATIENT_INELIGIBLE, assertThrows(SchedulingException.class,
                () -> useCase.change(new LifecycleCommand.StartAttention(ID, 2), as("SECRETARY_ASSISTANT")))
                .reason());
        assertEquals(0, store.calls);
        assertThrows(AppointmentNotFoundException.class, () -> useCase.change(
                new LifecycleCommand.StartAttention(UUID.randomUUID(), 1), as("SECRETARY_ASSISTANT")));

        eligibility.active = true;
        assertEquals(AppointmentStatus.EN_ATENCION, useCase.change(new LifecycleCommand.StartAttention(ID, 2),
                as("SECRETARY_ASSISTANT")).status());
    }

    @Test
    void noShowIsRecordedAfterTheEnd() {
        store.current = in(AppointmentStatus.PROGRAMADA, 1);
        var afterEnd = new ChangeAppointmentStatusUseCase(store, id -> Optional.of(store.current), eligibility,
                eligibility,
                STAFF_ONLY, Clock.fixed(START.plusHours(1).toInstant(), ZoneOffset.UTC));
        assertEquals(AppointmentStatus.NO_ASISTIO, afterEnd.change(new LifecycleCommand.NoShow(ID, 1, "Absent"),
                as("ADMINISTRATOR")).status());
        assertEquals("Absent", store.lastChange.reason());
    }

    @Test
    void reschedulingChecksThePatientAndTheNewIntervalWithoutTheAppointmentItself() {
        store.current = in(AppointmentStatus.CONFIRMADA, 2);
        OffsetDateTime next = START.plusDays(3);
        var command = new LifecycleCommand.Reschedule(ID, 2, next, next.plusMinutes(30), "Agenda", false);

        Appointment moved = useCase.change(command, as("SECRETARY_ASSISTANT"));
        assertEquals(AppointmentStatus.PROGRAMADA, moved.status());
        assertEquals(next, moved.startAt());
        assertEquals(ID, eligibility.moving);

        store.current = in(AppointmentStatus.PROGRAMADA, 2);
        eligibility.available = false;
        assertEquals(SchedulingException.Reason.SLOT_UNAVAILABLE, assertThrows(SchedulingException.class,
                () -> useCase.change(command, as("SECRETARY_ASSISTANT"))).reason());
        eligibility.active = false;
        int calls = store.calls;
        assertEquals(SchedulingException.Reason.PATIENT_INELIGIBLE, assertThrows(SchedulingException.class,
                () -> useCase.change(command, as("SECRETARY_ASSISTANT"))).reason());
        assertEquals(calls, store.calls);
    }

    private static final class FakeStore implements AppointmentLifecycleStore {
        Appointment current;
        StatusChange lastChange;
        int calls;

        @Override
        public Appointment apply(LifecycleCommand command, SchedulingContext context,
                                 Function<Appointment, Optional<Transition>> decide) {
            calls++;
            if (current == null || !current.id().equals(command.appointmentId())) {
                throw new AppointmentNotFoundException();
            }
            Optional<Transition> transition = decide.apply(current);
            transition.ifPresent(t -> {
                current = t.appointment();
                lastChange = t.change();
            });
            return current;
        }
    }

    private static final class FakeEligibility implements AppointmentEligibility, AppointmentAvailability {
        boolean active = true;
        boolean available = true;
        UUID moving;

        @Override
        public boolean isAvailable(UUID dentistId, OffsetDateTime startAt, OffsetDateTime endAt) {
            throw new AssertionError("Rescheduling must exclude the appointment being moved.");
        }

        @Override
        public boolean isAvailable(UUID dentistId, OffsetDateTime startAt, OffsetDateTime endAt, UUID movingId) {
            moving = movingId;
            return available;
        }

        @Override
        public boolean isPatientActive(UUID patientId) { return active; }

        @Override
        public boolean isDentistAuthorized(UUID dentistId) { return true; }
    }
}
