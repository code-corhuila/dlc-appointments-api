package co.edu.corhuila.dlc.appointments.domain.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DB-03B-B initial values and persistence reconstitution of the Appointment aggregate. */
class AppointmentStateTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PATIENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DENTIST = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Instant NOW = Instant.parse("2026-10-30T15:00:00Z");
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-01T09:00:00-05:00");

    @Test
    void schedulingSuppliesEveryInitialBusinessValueFromActorAndServerClock() {
        Appointment appointment = Appointment.schedule(ID, PATIENT, DENTIST, START, START.plusMinutes(30),
                "Checkup", ACTOR, NOW);

        assertAll(
                () -> assertEquals(AppointmentStatus.PROGRAMADA, appointment.status()),
                () -> assertEquals(ConfirmationStatus.PENDING, appointment.confirmationStatus()),
                () -> assertEquals(ReminderStatus.PENDING, appointment.reminderStatus()),
                () -> assertEquals(1, appointment.version()),
                () -> assertEquals(NOW, appointment.createdAt()),
                () -> assertEquals(ACTOR, appointment.createdBy()),
                () -> assertEquals(NOW, appointment.updatedAt()),
                () -> assertEquals(ACTOR, appointment.updatedBy()));
    }

    @Test
    void schedulingRequiresAFutureStart() {
        OffsetDateTime past = OffsetDateTime.parse("2026-10-30T09:00:00-05:00");
        var error = assertThrows(InvalidAppointmentException.class, () -> Appointment.schedule(ID, PATIENT, DENTIST,
                past, past.plusMinutes(30), "Checkup", ACTOR, NOW));
        assertEquals("startAt", error.field());

        OffsetDateTime exactlyNow = NOW.atOffset(START.getOffset());
        assertEquals("startAt", assertThrows(InvalidAppointmentException.class, () -> Appointment.schedule(ID,
                PATIENT, DENTIST, exactlyNow, exactlyNow.plusMinutes(30), "Checkup", ACTOR, NOW)).field());
    }

    @Test
    void schedulingRequiresTrustedActorAndClock() {
        assertThrows(NullPointerException.class, () -> Appointment.schedule(ID, PATIENT, DENTIST, START,
                START.plusMinutes(30), "Checkup", null, NOW));
        assertThrows(NullPointerException.class, () -> Appointment.schedule(ID, PATIENT, DENTIST, START,
                START.plusMinutes(30), "Checkup", ACTOR, null));
    }

    @Test
    void reconstitutionPreservesStoredStateIncludingPastAppointments() {
        OffsetDateTime past = OffsetDateTime.parse("2026-10-01T09:00:00-05:00");
        Instant updated = NOW.plusSeconds(60);
        UUID editor = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        var stored = new AppointmentSnapshot(ID, PATIENT, DENTIST, past, past.plusMinutes(30), "Checkup",
                AppointmentStatus.FINALIZADA, ConfirmationStatus.CONFIRMED, ReminderStatus.SENT, 4,
                NOW, ACTOR, updated, editor);

        Appointment appointment = Appointment.reconstitute(stored);

        assertEquals(stored, appointment.snapshot());
        assertEquals(past.minusHours(24).toInstant(), appointment.reminderDueAt().toInstant());
    }

    @Test
    void reconstitutionRejectsCorruptStoredState() {
        var valid = Appointment.schedule(ID, PATIENT, DENTIST, START, START.plusMinutes(30), "Checkup", ACTOR, NOW)
                .snapshot();
        assertThrows(IllegalStateException.class, () -> Appointment.reconstitute(new AppointmentSnapshot(
                ID, PATIENT, DENTIST, START, START.plusMinutes(30), "Checkup", AppointmentStatus.PROGRAMADA,
                ConfirmationStatus.PENDING, ReminderStatus.PENDING, 0, NOW, ACTOR, NOW, ACTOR)));
        assertThrows(IllegalStateException.class, () -> Appointment.reconstitute(new AppointmentSnapshot(
                ID, PATIENT, DENTIST, START, START.plusMinutes(30), "Checkup", AppointmentStatus.PROGRAMADA,
                ConfirmationStatus.PENDING, ReminderStatus.PENDING, 1, NOW, ACTOR, NOW.minusSeconds(1), ACTOR)));
        assertThrows(IllegalStateException.class, () -> Appointment.reconstitute(new AppointmentSnapshot(
                ID, PATIENT, DENTIST, START, START, "Checkup", valid.status(), valid.confirmationStatus(),
                valid.reminderStatus(), 1, NOW, ACTOR, NOW, ACTOR)));
        assertThrows(IllegalStateException.class, () -> Appointment.reconstitute(new AppointmentSnapshot(
                ID, PATIENT, DENTIST, START, START.plusMinutes(30), "Checkup", null, valid.confirmationStatus(),
                valid.reminderStatus(), 1, NOW, ACTOR, NOW, ACTOR)));
    }
}
