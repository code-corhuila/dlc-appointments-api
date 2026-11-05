package co.edu.corhuila.dlc.appointments.adapter.in.messaging;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.dlc.appointments.application.port.out.EventInbox;
import co.edu.corhuila.dlc.appointments.application.usecase.ConsumeEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;

/** Bounded retries for transient failures; invalid and rejected events go straight to the dead-letter queue. */
class RabbitEventConsumerTest {
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void aTransientFailureIsRetriedWithinTheAttemptsAndThenSucceeds() {
        var consumer = consumer(3, () -> {
            if (calls.incrementAndGet() < 3) throw new IllegalStateException("database unavailable");
            return EventInbox.Outcome.PROCESSED;
        });
        assertDoesNotThrow(() -> consumer.onMessage(message(valid())));
        assertEquals(3, calls.get());
    }

    @Test
    void exhaustedRetriesAreDeadLettered() {
        var consumer = consumer(3, () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("database unavailable");
        });
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> consumer.onMessage(message(valid())));
        assertEquals(3, calls.get());
    }

    @Test
    void rejectedAndInvalidEventsAreDeadLetteredWithoutRetries() {
        var rejecting = consumer(3, () -> {
            calls.incrementAndGet();
            throw new EventInbox.RejectedEventException("UNKNOWN_APPOINTMENT");
        });
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> rejecting.onMessage(message(valid())));
        assertEquals(1, calls.get());

        assertThrows(AmqpRejectAndDontRequeueException.class, () -> rejecting.onMessage(message("{}")));
        assertEquals(1, calls.get());
    }

    @Test
    void aDuplicateIsAcknowledged() {
        var consumer = consumer(3, () -> EventInbox.Outcome.DUPLICATE);
        assertDoesNotThrow(() -> consumer.onMessage(message(valid())));
    }

    @Test
    void theTopologyNamesFollowThePrefix() {
        var topology = new RabbitEventConsumer.Topology("clinical.events", "patients.events", "appointments");
        assertEquals("appointments.clinical-care-completed", topology.careQueue());
        assertEquals("appointments.patient-deactivated", topology.deactivationQueue());
        assertEquals("appointments.consumers.dlx", topology.deadLetterExchange());
        assertEquals("appointments.patient-deactivated.dlq", RabbitEventConsumer.Topology.dlq(topology.deactivationQueue()));
    }

    private RabbitEventConsumer consumer(int attempts, java.util.function.Supplier<EventInbox.Outcome> inbox) {
        var connections = new CachingConnectionFactory(URI.create("amqp://nobody:nothing@localhost:1"));
        return new RabbitEventConsumer(connections, new RabbitEventConsumer.Topology("c", "p", "it"),
                new ConsumeEvent((event, at) -> inbox.get(), Clock.systemUTC()), new EventEnvelopeReader(),
                attempts, Duration.ofMillis(1));
    }

    private static Message message(String body) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    static String valid() {
        String patient = UUID.randomUUID().toString();
        return """
                {"eventId":"%s","eventType":"PatientDeactivated","aggregateId":"%s","aggregateType":"Patient",
                 "occurredAt":"2026-11-04T15:30:00Z","version":1,"producer":"patients-service",
                 "payload":{"patientId":"%s","reasonCode":"ADMINISTRATIVE","deactivatedAt":"2026-11-04T15:30:00Z",
                 "deactivatedBy":"%s"},
                 "metadata":{"correlationId":"%s","causationId":"%s","actorUserId":null}}"""
                .formatted(UUID.randomUUID(), patient, patient, UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID());
    }
}
