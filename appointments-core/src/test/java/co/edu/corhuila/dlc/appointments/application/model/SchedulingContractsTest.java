package co.edu.corhuila.dlc.appointments.application.model;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SchedulingContractsTest {
    @Test
    void contextRequiresAllIdentifiersAndPreservesThem() {
        UUID actor = UUID.randomUUID(), session = UUID.randomUUID(), key = UUID.randomUUID();
        var context = new SchedulingContext(actor, session, key);
        assertEquals(actor, context.actorUserId());
        assertEquals(session, context.sessionId());
        assertEquals(key, context.idempotencyKey());
        assertThrows(NullPointerException.class, () -> new SchedulingContext(null, session, key));
        assertThrows(NullPointerException.class, () -> new SchedulingContext(actor, null, key));
        assertThrows(NullPointerException.class, () -> new SchedulingContext(actor, session, null));
    }

    @Test
    void resultRequiresAppointmentAndPositiveOriginalVersion() {
        var start = OffsetDateTime.parse("2026-11-01T09:00:00-05:00");
        var appointment = Appointment.schedule(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                start, start.plusMinutes(30), "Checkup");
        assertSame(appointment, new SchedulingResult(appointment, 1).appointment());
        assertEquals(7, new SchedulingResult(appointment, 7).version());
        assertThrows(NullPointerException.class, () -> new SchedulingResult(null, 1));
        assertThrows(IllegalArgumentException.class, () -> new SchedulingResult(appointment, 0));
        assertThrows(IllegalArgumentException.class, () -> new SchedulingResult(appointment, -1));
    }
}
