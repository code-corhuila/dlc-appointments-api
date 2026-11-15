package co.edu.corhuila.dlc.appointments.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.dlc.appointments.application.model.InboundEvent;
import co.edu.corhuila.dlc.appointments.application.port.out.EventInbox;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsumeEventTest {
    private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");

    @Test
    void theEventIsRecordedAtServerTimeAndTheInboxOutcomeIsReturned() {
        List<Instant> received = new ArrayList<>();
        EventInbox inbox = (event, at) -> {
            received.add(at);
            return EventInbox.Outcome.DUPLICATE;
        };
        var consume = new ConsumeEvent(inbox, Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals(EventInbox.Outcome.DUPLICATE, consume.handle(deactivation()));
        assertEquals(List.of(NOW), received);
    }

    @Test
    void factsMustMatchTheirCanonicalEnvelope() {
        UUID patient = UUID.randomUUID();
        var fact = new InboundEvent.PatientDeactivation(patient, NOW);
        assertThrows(IllegalArgumentException.class, () -> new InboundEvent(UUID.randomUUID(),
                "ClinicalCareCompleted", 1, "patients-service", patient, UUID.randomUUID(), fact));
        assertThrows(IllegalArgumentException.class, () -> new InboundEvent(UUID.randomUUID(),
                "PatientDeactivated", 1, "patients-service", UUID.randomUUID(), UUID.randomUUID(), fact));
        assertThrows(NullPointerException.class, () -> new InboundEvent(null,
                "PatientDeactivated", 1, "patients-service", patient, UUID.randomUUID(), fact));
    }

    private static InboundEvent deactivation() {
        UUID patient = UUID.randomUUID();
        return new InboundEvent(UUID.randomUUID(), "PatientDeactivated", 1, "patients-service", patient,
                UUID.randomUUID(), new InboundEvent.PatientDeactivation(patient, NOW));
    }
}
