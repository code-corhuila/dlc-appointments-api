package co.edu.corhuila.dlc.appointments.domain.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** HU-APT-002 state machine: APT-003/004 (24 h, override), APT-005/010 (reminder), APT-007/011 (terminals). */
class AppointmentLifecycleTest {
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final OffsetDateTime START = OffsetDateTime.parse("2031-05-10T09:00:00-05:00");
    private static final Instant WINDOW_EDGE = START.toInstant().minus(Duration.ofHours(24));
    private static final Instant EARLY = WINDOW_EDGE.minusSeconds(3600);
    private static final OffsetDateTime NEW_START = OffsetDateTime.parse("2031-05-20T10:00:00-05:00");

    private static Appointment in(AppointmentStatus status) {
        Instant created = Instant.parse("2031-04-01T12:00:00Z");
        UUID creator = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        return Appointment.reconstitute(new AppointmentSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"), START, START.plusMinutes(30), "Checkup",
                status, status == AppointmentStatus.PROGRAMADA ? ConfirmationStatus.PENDING : ConfirmationStatus.CONFIRMED,
                ReminderStatus.PENDING, 3, created, creator, created, creator));
    }

    private static void onlyFrom(EnumSet<AppointmentStatus> allowed, Function<Appointment, Transition> operation) {
        for (AppointmentStatus status : EnumSet.complementOf(allowed)) {
            assertThrows(InvalidStatusTransitionException.class, () -> operation.apply(in(status)), status.name());
        }
        for (AppointmentStatus status : allowed) {
            assertEquals(4, operation.apply(in(status)).appointment().version(), status.name());
        }
    }

    @Test
    void confirmationTurnsProgramadaIntoConfirmadaAndAuditsIt() {
        Transition t = in(AppointmentStatus.PROGRAMADA).confirm(ConfirmationChannel.PHONE, ACTOR, EARLY);

        assertAll(
                () -> assertEquals(AppointmentStatus.CONFIRMADA, t.appointment().status()),
                () -> assertEquals(ConfirmationStatus.CONFIRMED, t.appointment().confirmationStatus()),
                () -> assertEquals(4, t.appointment().version()),
                () -> assertEquals(EARLY, t.appointment().updatedAt()),
                () -> assertEquals(ACTOR, t.appointment().updatedBy()),
                () -> assertEquals(new StatusChange(LifecycleOperation.CONFIRM, AppointmentStatus.PROGRAMADA,
                        AppointmentStatus.CONFIRMADA, null, null, null, null, null, false), t.change()));
        onlyFrom(EnumSet.of(AppointmentStatus.PROGRAMADA), a -> a.confirm(ConfirmationChannel.EMAIL, ACTOR, EARLY));
    }

    @Test
    void cancellationIsAllowedUpToExactlyTwentyFourHoursBeforeStart() {
        Transition t = in(AppointmentStatus.CONFIRMADA)
                .cancel("Patient travels", false, StaffRole.SECRETARY_ASSISTANT, ACTOR, WINDOW_EDGE);

        assertEquals(AppointmentStatus.CANCELADA, t.appointment().status());
        assertEquals(new StatusChange(LifecycleOperation.CANCEL, AppointmentStatus.CONFIRMADA,
                AppointmentStatus.CANCELADA, null, null, null, null, "Patient travels", false), t.change());
        assertThrows(AppointmentConflictException.class, () -> in(AppointmentStatus.PROGRAMADA)
                .cancel("Late", false, StaffRole.ADMINISTRATOR, ACTOR, WINDOW_EDGE.plusSeconds(1)));
        onlyFrom(EnumSet.of(AppointmentStatus.PROGRAMADA, AppointmentStatus.CONFIRMADA),
                a -> a.cancel("Reason", false, StaffRole.SECRETARY_ASSISTANT, ACTOR, EARLY));
    }

    @Test
    void onlyAnAdministratorMayOverrideTheWindowAndOnlyAnUsedOverrideIsAudited() {
        Instant late = WINDOW_EDGE.plusSeconds(60);
        assertTrue(in(AppointmentStatus.PROGRAMADA)
                .cancel("Emergency", true, StaffRole.ADMINISTRATOR, ACTOR, late).change().override());
        assertFalse(in(AppointmentStatus.PROGRAMADA)
                .cancel("Early", true, StaffRole.ADMINISTRATOR, ACTOR, EARLY).change().override());
        var denied = assertThrows(SchedulingException.class, () -> in(AppointmentStatus.PROGRAMADA)
                .cancel("Emergency", true, StaffRole.SECRETARY_ASSISTANT, ACTOR, EARLY));
        assertEquals(SchedulingException.Reason.AUTHORIZATION_DENIED, denied.reason());
    }

    @Test
    void cancellationAndNoShowRequireAReasonOfOneToOneThousandCharacters() {
        for (String reason : new String[]{null, " ", "x".repeat(1001)}) {
            assertEquals("reason", assertThrows(InvalidAppointmentException.class, () -> in(AppointmentStatus.PROGRAMADA)
                    .cancel(reason, false, StaffRole.ADMINISTRATOR, ACTOR, EARLY)).field());
            assertEquals("reason", assertThrows(InvalidAppointmentException.class, () -> in(AppointmentStatus.PROGRAMADA)
                    .markNoShow(reason, ACTOR, START.plusHours(1).toInstant())).field());
        }
    }

    @Test
    void reschedulingMovesTheIntervalResetsConfirmationAndRecalculatesTheReminder() {
        Transition t = in(AppointmentStatus.CONFIRMADA).reschedule(NEW_START, NEW_START.plusMinutes(45), "Agenda",
                false, StaffRole.SECRETARY_ASSISTANT, ACTOR, EARLY);

        assertAll(
                () -> assertEquals(AppointmentStatus.PROGRAMADA, t.appointment().status()),
                () -> assertEquals(ConfirmationStatus.PENDING, t.appointment().confirmationStatus()),
                () -> assertEquals(ReminderStatus.PENDING, t.appointment().reminderStatus()),
                () -> assertEquals(NEW_START, t.appointment().startAt()),
                () -> assertEquals(NEW_START.minusHours(24), t.appointment().reminderDueAt()),
                () -> assertEquals(new StatusChange(LifecycleOperation.RESCHEDULE, AppointmentStatus.CONFIRMADA,
                        AppointmentStatus.PROGRAMADA, START, START.plusMinutes(30), NEW_START,
                        NEW_START.plusMinutes(45), "Agenda", false), t.change()));
        onlyFrom(EnumSet.of(AppointmentStatus.PROGRAMADA, AppointmentStatus.CONFIRMADA), a -> a.reschedule(NEW_START,
                NEW_START.plusMinutes(30), "Agenda", false, StaffRole.ADMINISTRATOR, ACTOR, EARLY));
    }

    @Test
    void reschedulingValidatesTheNewIntervalAndTheWindowOfTheCurrentStart() {
        Appointment a = in(AppointmentStatus.PROGRAMADA);
        assertEquals("startAt", assertThrows(InvalidAppointmentException.class, () -> a.reschedule(START,
                START.plusMinutes(30), "Same", false, StaffRole.ADMINISTRATOR, ACTOR, EARLY)).field());
        assertEquals("endAt", assertThrows(InvalidAppointmentException.class, () -> a.reschedule(NEW_START,
                NEW_START, "Empty", false, StaffRole.ADMINISTRATOR, ACTOR, EARLY)).field());
        OffsetDateTime past = EARLY.atOffset(START.getOffset()).minusHours(1);
        assertEquals("startAt", assertThrows(InvalidAppointmentException.class, () -> a.reschedule(past,
                past.plusMinutes(30), "Past", false, StaffRole.ADMINISTRATOR, ACTOR, EARLY)).field());
        assertThrows(AppointmentConflictException.class, () -> a.reschedule(NEW_START, NEW_START.plusMinutes(30),
                "Late", false, StaffRole.ADMINISTRATOR, ACTOR, WINDOW_EDGE.plusSeconds(1)));
        assertTrue(a.reschedule(NEW_START, NEW_START.plusMinutes(30), "Late", true, StaffRole.ADMINISTRATOR, ACTOR,
                WINDOW_EDGE.plusSeconds(1)).change().override());
    }

    @Test
    void attentionStartsOnlyFromConfirmadaAndCompletionOnlyFromEnAtencion() {
        Transition started = in(AppointmentStatus.CONFIRMADA).startAttention(ACTOR, EARLY);
        assertEquals(AppointmentStatus.EN_ATENCION, started.appointment().status());
        assertEquals(LifecycleOperation.START_ATTENTION, started.change().operation());
        onlyFrom(EnumSet.of(AppointmentStatus.CONFIRMADA), a -> a.startAttention(ACTOR, EARLY));

        Transition completed = in(AppointmentStatus.EN_ATENCION).complete(ACTOR, EARLY);
        assertEquals(AppointmentStatus.FINALIZADA, completed.appointment().status());
        assertNull(completed.change().reason());
        onlyFrom(EnumSet.of(AppointmentStatus.EN_ATENCION), a -> a.complete(ACTOR, EARLY));
    }

    @Test
    void noShowIsRecordedOnlyOnceTheAppointmentHasEndedAndNeverForAttention() {
        Instant end = START.plusMinutes(30).toInstant();
        assertThrows(AppointmentConflictException.class,
                () -> in(AppointmentStatus.CONFIRMADA).markNoShow("Absent", ACTOR, end.minusSeconds(1)));
        Transition t = in(AppointmentStatus.CONFIRMADA).markNoShow("Absent", ACTOR, end);
        assertEquals(new StatusChange(LifecycleOperation.NO_SHOW, AppointmentStatus.CONFIRMADA,
                AppointmentStatus.NO_ASISTIO, null, null, null, null, "Absent", false), t.change());
        onlyFrom(EnumSet.of(AppointmentStatus.PROGRAMADA, AppointmentStatus.CONFIRMADA),
                a -> a.markNoShow("Absent", ACTOR, end));
    }

    @Test
    void transitionsRequireTheVerifiedActorAndTheServerClock() {
        assertThrows(NullPointerException.class,
                () -> in(AppointmentStatus.PROGRAMADA).confirm(ConfirmationChannel.PHONE, null, EARLY));
        assertThrows(NullPointerException.class,
                () -> in(AppointmentStatus.PROGRAMADA).confirm(ConfirmationChannel.PHONE, ACTOR, null));
        assertThrows(NullPointerException.class,
                () -> in(AppointmentStatus.PROGRAMADA).confirm(null, ACTOR, EARLY));
    }
}
