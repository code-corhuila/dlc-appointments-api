package co.edu.corhuila.dlc.appointments.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Availability replacement rules (contract 1.4.0, decisions G1-G2). */
class DentistAvailabilityTest {
    private static final UUID DENTIST = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2031-01-01T00:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(60);

    @Test
    void firstReplacementWithExpectedVersionOneCreatesVersionOne() {
        DentistAvailability created = replace(Optional.empty(), List.of(i("08:00", "12:00")), List.of(), 1, List.of());
        assertEquals(1, created.version());
        assertEquals(DENTIST, created.dentistId());
        assertEquals(NOW, created.createdAt());
        assertEquals(ACTOR, created.createdBy());
        assertEquals(List.of(i("08:00", "12:00")), created.intervals());
    }

    @Test
    void laterReplacementRequiresTheCurrentVersionAndIncrementsIt() {
        DentistAvailability v1 = replace(Optional.empty(), List.of(i("08:00", "12:00")), List.of(), 1, List.of());
        DentistAvailability v2 = DentistAvailability.replace(Optional.of(v1), DENTIST, List.of(i("09:00", "11:00")),
                List.of(), 1, List.of(), UUID.randomUUID(), LATER, UUID.randomUUID());
        assertEquals(2, v2.version());
        assertEquals(v1.id(), v2.id());
        assertEquals(NOW, v2.createdAt());
        assertEquals(LATER, v2.updatedAt());
        assertThrows(StaleVersionException.class, () -> DentistAvailability.replace(Optional.of(v2), DENTIST,
                List.of(), List.of(), 1, List.of(), ACTOR, LATER, UUID.randomUUID()));
        assertThrows(StaleVersionException.class, () -> replace(Optional.empty(), List.of(), List.of(), 2, List.of()));
    }

    @Test
    void invalidOrOverlappingIntervalsAreReportedByPosition() {
        var error = assertThrows(InvalidAvailabilityException.class, () -> replace(Optional.empty(),
                List.of(i("08:00", "12:00"), i("11:00", "13:00"), i("14:00", "14:00")),
                List.of(new TimeInterval(null, at("09:00"))), 1, List.of()));
        assertEquals(List.of("intervals[1]", "intervals[2]", "blockedIntervals[0]"),
                error.errors().stream().map(FieldError::field).toList());
    }

    @Test
    void adjacentIntervalsDoNotOverlap() {
        assertEquals(2, replace(Optional.empty(), List.of(i("10:00", "11:00"), i("08:00", "10:00")), List.of(), 1,
                List.of()).intervals().size());
    }

    @Test
    void replacementCannotLeaveAnActiveAppointmentUncovered() {
        List<TimeInterval> booked = List.of(i("09:00", "09:30"));
        assertEquals(1, replace(Optional.empty(), List.of(i("08:00", "12:00")), List.of(i("10:00", "11:00")), 1,
                booked).version());
        var outside = assertThrows(SchedulingException.class, () -> replace(Optional.empty(),
                List.of(i("10:00", "12:00")), List.of(), 1, booked));
        assertEquals(SchedulingException.Reason.SLOT_UNAVAILABLE, outside.reason());
        assertThrows(SchedulingException.class, () -> replace(Optional.empty(), List.of(i("08:00", "12:00")),
                List.of(i("09:15", "09:45")), 1, booked));
    }

    @Test
    void coverageRequiresAContainingIntervalAndNoOverlappingBlock() {
        DentistAvailability a = replace(Optional.empty(), List.of(i("08:00", "12:00")), List.of(i("10:00", "11:00")),
                1, List.of());
        assertTrue(a.covers(i("08:00", "10:00")));
        assertFalse(a.covers(i("09:30", "10:30")));
        assertFalse(a.covers(i("11:30", "12:30")));
    }

    private static DentistAvailability replace(Optional<DentistAvailability> current, List<TimeInterval> intervals,
            List<TimeInterval> blocked, long expectedVersion, List<TimeInterval> active) {
        return DentistAvailability.replace(current, DENTIST, intervals, blocked, expectedVersion, active, ACTOR, NOW,
                UUID.randomUUID());
    }

    private static TimeInterval i(String start, String end) {
        return new TimeInterval(at(start), at(end));
    }

    private static OffsetDateTime at(String time) {
        return OffsetDateTime.parse("2031-02-03T" + time + ":00-05:00");
    }
}
