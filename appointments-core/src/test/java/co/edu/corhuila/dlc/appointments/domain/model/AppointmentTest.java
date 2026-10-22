package co.edu.corhuila.dlc.appointments.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppointmentTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PATIENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DENTIST = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-01T09:00:00-05:00");
    private static final OffsetDateTime END = START.plusMinutes(30);

    @Test
    void schedulesWithContractIdentifiersIntervalReasonAndInitialStatus() {
        Appointment appointment = Appointment.schedule(ID, PATIENT, DENTIST, START, END, "Checkup");

        assertAll(
                () -> assertEquals(ID, appointment.id()),
                () -> assertEquals(PATIENT, appointment.patientId()),
                () -> assertEquals(DENTIST, appointment.dentistId()),
                () -> assertEquals(START.toInstant(), appointment.startAt().toInstant()),
                () -> assertEquals(END.toInstant(), appointment.endAt().toInstant()),
                () -> assertEquals("Checkup", appointment.reason()),
                () -> assertEquals(AppointmentStatus.PROGRAMADA, appointment.status()));
    }
    @Test
    void rejectsMissingRequiredValuesWithTypedErrors() {
        assertAll(
                () -> assertInvalid("id", null, PATIENT, DENTIST, START, END, "Checkup"),
                () -> assertInvalid("patientId", ID, null, DENTIST, START, END, "Checkup"),
                () -> assertInvalid("dentistId", ID, PATIENT, null, START, END, "Checkup"),
                () -> assertInvalid("startAt", ID, PATIENT, DENTIST, null, END, "Checkup"),
                () -> assertInvalid("endAt", ID, PATIENT, DENTIST, START, null, "Checkup"),
                () -> assertInvalid("reason", ID, PATIENT, DENTIST, START, END, null));
    }

    @Test
    void requiresNonEmptyReasonOfAtMost500Characters() {
        for (String reason : new String[]{"", " ", "\t\n", "x".repeat(501)}) {
            assertInvalid("reason", ID, PATIENT, DENTIST, START, END, reason);
        }
        for (String reason : new String[]{"x", "x".repeat(500)}) {
            assertEquals(reason, Appointment.schedule(ID, PATIENT, DENTIST, START, END, reason).reason());
        }
    }

    @Test
    void comparesIntervalByInstantRatherThanLocalTime() {
        assertInvalid("endAt", ID, PATIENT, DENTIST, START, START, "Checkup");
        assertInvalid("endAt", ID, PATIENT, DENTIST, START, START.minusSeconds(1), "Checkup");
        assertInvalid("endAt", ID, PATIENT, DENTIST, START,
                OffsetDateTime.parse("2026-11-01T14:00:00Z"), "Checkup");
        Appointment appointment = Appointment.schedule(ID, PATIENT, DENTIST, START,
                OffsetDateTime.parse("2026-11-01T14:30:00Z"), "Checkup");
        assertEquals(END.toInstant(), appointment.endAt().toInstant());
    }

    @Test
    void apt006DerivesReminderExactly24HoursBeforeStartAcrossDayBoundary() {
        OffsetDateTime start = OffsetDateTime.parse("2026-11-01T00:15:00-05:00");
        Appointment appointment = Appointment.schedule(ID, PATIENT, DENTIST,
                start, start.plusMinutes(30), "Checkup");
        assertEquals(OffsetDateTime.parse("2026-10-31T00:15:00-05:00").toInstant(),
                appointment.reminderDueAt().toInstant());
        assertEquals(java.time.Duration.ofHours(24),
                java.time.Duration.between(appointment.reminderDueAt(), appointment.startAt()));
    }

    @Test
    void acceptsReasonOfExactly500SupplementaryCodePoints() {
        String reason = "\uD83D\uDE00".repeat(500);
        assertEquals(1000, reason.length());
        assertEquals(500, reason.codePointCount(0, reason.length()));

        Appointment appointment = Appointment.schedule(ID, PATIENT, DENTIST, START, END, reason);

        assertEquals(reason, appointment.reason());
    }

    @Test
    void rejectsReasonOf501SupplementaryCodePointsWithReasonField() {
        String reason = "\uD83D\uDE00".repeat(501);
        assertEquals(1002, reason.length());
        assertEquals(501, reason.codePointCount(0, reason.length()));

        assertInvalid("reason", ID, PATIENT, DENTIST, START, END, reason);
    }

    private void assertInvalid(String field, UUID id, UUID patient, UUID dentist,
                               OffsetDateTime start, OffsetDateTime end, String reason) {
        InvalidAppointmentException error = assertThrows(InvalidAppointmentException.class,
                () -> Appointment.schedule(id, patient, dentist, start, end, reason));
        assertEquals(field, error.field());
        assertFalse(error.getMessage().isBlank());
    }
}

