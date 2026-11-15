package co.edu.corhuila.dlc.appointments.adapter.in.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import co.edu.corhuila.dlc.appointments.adapter.in.messaging.EventEnvelopeReader.InvalidEventException;
import co.edu.corhuila.dlc.appointments.application.model.InboundEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Strict v1 envelopes of ClinicalCareCompleted and PatientDeactivated (dlc-docs/07-api/contracts/events). */
class EventEnvelopeReaderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RECORD = UUID.randomUUID().toString();
    private static final String PATIENT = UUID.randomUUID().toString();
    private final EventEnvelopeReader reader = new EventEnvelopeReader();

    @Test
    void aValidCareCompletionIsReadWithItsIdentifiersAndCorrelation() throws Exception {
        ObjectNode event = completion();
        InboundEvent read = reader.read(bytes(event));

        assertEquals(UUID.fromString(event.get("eventId").asText()), read.eventId());
        assertEquals(UUID.fromString(event.at("/metadata/correlationId").asText()), read.correlationId());
        var fact = assertInstanceOf(InboundEvent.CareCompletionDeclared.class, read.fact());
        assertEquals(UUID.fromString(RECORD), fact.clinicalRecordId());
        assertEquals(Instant.parse("2026-11-04T15:30:00Z"), fact.completedAt());
        assertEquals(4, fact.clinicalVersion());
    }

    @Test
    void aValidDeactivationWithANullActorIsRead() throws Exception {
        ObjectNode event = deactivation();
        ((ObjectNode) event.get("metadata")).putNull("actorUserId");
        var fact = assertInstanceOf(InboundEvent.PatientDeactivation.class, reader.read(bytes(event)).fact());
        assertEquals(UUID.fromString(PATIENT), fact.patientId());
    }

    @Test
    void unknownTypesAndVersionsAreUnsupported() throws Exception {
        ObjectNode other = completion().put("eventType", "ProcedureCompleted");
        assertEquals("UNSUPPORTED_EVENT", code(other));
        assertEquals("UNSUPPORTED_EVENT", code(completion().put("version", 2)));
    }

    @Test
    void anythingOutsideTheSchemaIsInvalid() throws Exception {
        assertEquals("MALFORMED_EVENT", assertThrows(InvalidEventException.class,
                () -> reader.read("{not json".getBytes(StandardCharsets.UTF_8))).code());
        assertEquals("MALFORMED_EVENT", assertThrows(InvalidEventException.class,
                () -> reader.read("[]".getBytes(StandardCharsets.UTF_8))).code());
        assertEquals("INVALID_EVENT", code(completion().put("extra", 1)));
        assertEquals("INVALID_EVENT", code(without(completion(), "occurredAt")));
        assertEquals("INVALID_EVENT", code(completion().put("producer", "patients-service")));
        assertEquals("INVALID_EVENT", code(completion().put("aggregateType", "Patient")));
        assertEquals("INVALID_EVENT", code(completion().put("aggregateId", UUID.randomUUID().toString())));
        assertEquals("INVALID_EVENT", code(completion().put("eventId", "not-a-uuid")));
        assertEquals("INVALID_EVENT", code(completion().put("occurredAt", "2026-11-04T15:30:00")));

        ObjectNode payload = completion();
        ((ObjectNode) payload.get("payload")).put("clinicalNotes", "x");
        assertEquals("INVALID_EVENT", code(payload));
        ObjectNode duplicated = completion();
        String procedure = UUID.randomUUID().toString();
        ((ObjectNode) duplicated.get("payload")).putArray("procedureIds").add(procedure).add(procedure);
        assertEquals("INVALID_EVENT", code(duplicated));
        ObjectNode version = completion();
        ((ObjectNode) version.get("payload")).put("clinicalVersion", 0);
        assertEquals("INVALID_EVENT", code(version));
        ObjectNode reason = deactivation();
        ((ObjectNode) reason.get("payload")).put("reasonCode", "BANNED");
        assertEquals("INVALID_EVENT", code(reason));
        ObjectNode metadata = deactivation();
        ((ObjectNode) metadata.get("metadata")).remove("causationId");
        assertEquals("INVALID_EVENT", code(metadata));
    }

    private String code(ObjectNode event) throws Exception {
        byte[] body = bytes(event);
        return assertThrows(InvalidEventException.class, () -> reader.read(body)).code();
    }

    private static ObjectNode completion() {
        ObjectNode event = envelope("ClinicalCareCompleted", "ClinicalRecord", "clinical-service", RECORD);
        ObjectNode payload = event.putObject("payload");
        payload.put("clinicalRecordId", RECORD).put("consultationId", UUID.randomUUID().toString())
                .put("appointmentId", UUID.randomUUID().toString()).put("patientId", UUID.randomUUID().toString())
                .put("dentistId", UUID.randomUUID().toString());
        payload.putArray("procedureIds").add(UUID.randomUUID().toString());
        payload.putArray("manualChargeIds");
        payload.put("completedAt", "2026-11-04T10:30:00-05:00").put("clinicalVersion", 4);
        return metadata(event);
    }

    private static ObjectNode deactivation() {
        ObjectNode event = envelope("PatientDeactivated", "Patient", "patients-service", PATIENT);
        event.putObject("payload").put("patientId", PATIENT).put("reasonCode", "PATIENT_REQUEST")
                .put("deactivatedAt", "2026-11-04T15:30:00Z").put("deactivatedBy", UUID.randomUUID().toString());
        return metadata(event);
    }

    private static ObjectNode envelope(String type, String aggregateType, String producer, String aggregate) {
        return JSON.createObjectNode().put("eventId", UUID.randomUUID().toString()).put("eventType", type)
                .put("aggregateId", aggregate).put("aggregateType", aggregateType)
                .put("occurredAt", "2026-11-04T15:30:01.123Z").put("version", 1).put("producer", producer);
    }

    private static ObjectNode metadata(ObjectNode event) {
        event.putObject("metadata").put("correlationId", UUID.randomUUID().toString())
                .put("causationId", UUID.randomUUID().toString()).put("actorUserId", UUID.randomUUID().toString());
        return event;
    }

    private static ObjectNode without(ObjectNode event, String field) {
        event.remove(field);
        return event;
    }

    private static byte[] bytes(ObjectNode event) throws Exception {
        return JSON.writeValueAsBytes(event);
    }
}
