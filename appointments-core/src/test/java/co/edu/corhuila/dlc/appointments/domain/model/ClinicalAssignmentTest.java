package co.edu.corhuila.dlc.appointments.domain.model;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-APT-004 / REL-001/002: ongoing assignment metadata, no self-assignment, audited ending. */
class ClinicalAssignmentTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID PATIENT = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID DENTIST = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID STAFF = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Instant NOW = Instant.parse("2031-05-01T12:00:00Z");

    @Test
    void startingRecordsAnActiveVersionOneAsTheVerifiedActor() {
        ClinicalAssignment a = ClinicalAssignment.start(ID, PATIENT, DENTIST, "Orthodontic plan", STAFF, NOW);
        assertAll(
                () -> assertEquals(AssignmentStatus.ACTIVE, a.status()),
                () -> assertEquals(1, a.version()),
                () -> assertEquals(NOW, a.startedAt()),
                () -> assertEquals(STAFF, a.startedBy()),
                () -> assertNull(a.endedAt()),
                () -> assertNull(a.endReason()));
    }

    @Test
    void nobodyAssignsThemselvesAndTheReasonIsBounded() {
        assertEquals(SchedulingException.Reason.AUTHORIZATION_DENIED, assertThrows(SchedulingException.class,
                () -> ClinicalAssignment.start(ID, PATIENT, DENTIST, "Self", DENTIST, NOW)).reason());
        for (String reason : new String[]{null, " ", "x".repeat(1001)}) {
            assertEquals("reason", assertThrows(InvalidAppointmentException.class,
                    () -> ClinicalAssignment.start(ID, PATIENT, DENTIST, reason, STAFF, NOW)).field());
        }
    }

    @Test
    void endingNeedsTheCurrentVersionAndHappensOnce() {
        ClinicalAssignment active = ClinicalAssignment.start(ID, PATIENT, DENTIST, "Plan", STAFF, NOW);
        Instant later = NOW.plusSeconds(60);
        assertThrows(StaleVersionException.class, () -> active.end("Done", 2, STAFF, later));
        assertEquals("reason", assertThrows(InvalidAppointmentException.class,
                () -> active.end(" ", 1, STAFF, later)).field());

        ClinicalAssignment ended = active.end("Treatment finished", 1, STAFF, later);
        assertAll(
                () -> assertEquals(AssignmentStatus.ENDED, ended.status()),
                () -> assertEquals(2, ended.version()),
                () -> assertEquals(later, ended.endedAt()),
                () -> assertEquals(STAFF, ended.endedBy()),
                () -> assertEquals("Treatment finished", ended.endReason()),
                () -> assertEquals("Plan", ended.reason()));
        assertThrows(InvalidStatusTransitionException.class, () -> ended.end("Again", 2, STAFF, later));
        assertThrows(InvalidStatusTransitionException.class, () -> ended.end("Again", 1, STAFF, later));
    }
}
