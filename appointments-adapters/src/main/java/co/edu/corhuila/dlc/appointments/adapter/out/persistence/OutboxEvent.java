package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

/** An integration event to commit with the business change (Norma 5.3.11); payloadJson is a JSON object. */
public record OutboxEvent(UUID eventId, String eventType, int eventVersion, String aggregateType, UUID aggregateId,
        String payloadJson, UUID correlationId, UUID causationId, UUID actorUserId, Instant occurredAt) { }
