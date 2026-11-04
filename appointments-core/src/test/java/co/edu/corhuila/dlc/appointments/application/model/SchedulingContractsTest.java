package co.edu.corhuila.dlc.appointments.application.model;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingContractsTest {
    @Test
    void contextRequiresAllIdentifiersAndPreservesThem() {
        UUID actor = UUID.randomUUID(), session = UUID.randomUUID();
        String key = "retry-key-0001";
        var context = new SchedulingContext(actor, session, key);
        assertEquals(actor, context.actorUserId());
        assertEquals(session, context.sessionId());
        assertEquals(key, context.idempotencyKey());
        assertThrows(NullPointerException.class, () -> new SchedulingContext(null, session, key));
        assertThrows(NullPointerException.class, () -> new SchedulingContext(actor, null, key));
        assertThrows(NullPointerException.class, () -> new SchedulingContext(actor, session, null));
    }

    @Test
    void idempotencyKeyHas8To128CharactersAsNorma538Requires() {
        UUID actor = UUID.randomUUID(), session = UUID.randomUUID();
        assertEquals(8, new SchedulingContext(actor, session, "k".repeat(8)).idempotencyKey().length());
        assertEquals(128, new SchedulingContext(actor, session, "k".repeat(128)).idempotencyKey().length());
        assertEquals("6f178b1a-9c0d-4c23-96ad-1e8da3b2ef6d",
                new SchedulingContext(actor, session, "6f178b1a-9c0d-4c23-96ad-1e8da3b2ef6d").idempotencyKey());
        assertThrows(IllegalArgumentException.class, () -> new SchedulingContext(actor, session, "k".repeat(7)));
        assertThrows(IllegalArgumentException.class, () -> new SchedulingContext(actor, session, "k".repeat(129)));
    }

    @Test
    void resultRequiresAppointmentAndPositiveOriginalVersion() {
        var start = OffsetDateTime.parse("2026-11-01T09:00:00-05:00");
        var appointment = Appointment.schedule(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                start, start.plusMinutes(30), "Checkup", UUID.randomUUID(), start.toInstant().minusSeconds(3600));
        assertSame(appointment, new SchedulingResult(appointment, 1).appointment());
        assertEquals(7, new SchedulingResult(appointment, 7).version());
        assertThrows(NullPointerException.class, () -> new SchedulingResult(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new SchedulingResult(appointment, 0));
        assertThrows(IllegalArgumentException.class, () -> new SchedulingResult(appointment, -1));
    }
}
