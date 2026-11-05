package co.edu.corhuila.dlc.appointments.adapter.in.messaging;

import co.edu.corhuila.dlc.appointments.application.model.InboundEvent;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict reader of the v1 schemas of ClinicalCareCompleted and PatientDeactivated (dlc-docs/07-api/contracts/events):
 * every required field, no additional property, constant type/producer/aggregate, UUID and RFC 3339 formats, and
 * aggregateId equal to the payload's aggregate. Failures carry only a safe code, never message content.
 */
public final class EventEnvelopeReader {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> ENVELOPE = Set.of("eventId", "eventType", "aggregateId", "aggregateType",
            "occurredAt", "version", "producer", "payload", "metadata");
    private static final Set<String> METADATA = Set.of("correlationId", "causationId", "actorUserId");
    private static final Set<String> COMPLETION = Set.of("clinicalRecordId", "consultationId", "appointmentId",
            "patientId", "dentistId", "procedureIds", "manualChargeIds", "completedAt", "clinicalVersion");
    private static final Set<String> DEACTIVATION = Set.of("patientId", "reasonCode", "deactivatedAt",
            "deactivatedBy");
    private static final Set<String> REASONS = Set.of("ADMINISTRATIVE", "DUPLICATE_RECORD", "PATIENT_REQUEST",
            "OTHER");
    private static final Map<String, String[]> SUPPORTED = Map.of(
            "ClinicalCareCompleted", new String[] {"ClinicalRecord", "clinical-service"},
            "PatientDeactivated", new String[] {"Patient", "patients-service"});

    public InboundEvent read(byte[] body) {
        JsonNode event;
        try {
            event = JSON.readTree(Objects.requireNonNull(body, "body"));
        } catch (IOException malformed) {
            throw new InvalidEventException("MALFORMED_EVENT");
        }
        if (event == null || !event.isObject()) throw new InvalidEventException("MALFORMED_EVENT");
        String type = event.path("eventType").isTextual() ? event.get("eventType").asText() : null;
        JsonNode version = event.path("version");
        if (type != null && (!SUPPORTED.containsKey(type) || version.isIntegralNumber() && version.asLong() != 1)) {
            throw new InvalidEventException("UNSUPPORTED_EVENT");
        }
        if (type == null || !version.isIntegralNumber()) throw invalid();
        exactly(event, ENVELOPE);
        String[] fixed = SUPPORTED.get(type);
        text(event, "aggregateType", fixed[0]);
        text(event, "producer", fixed[1]);
        instant(event, "occurredAt");
        JsonNode metadata = exactly(event.get("metadata"), METADATA);
        uuid(metadata, "causationId");
        if (!metadata.get("actorUserId").isNull()) uuid(metadata, "actorUserId");
        JsonNode payload = event.get("payload");
        InboundEvent.Fact fact = "ClinicalCareCompleted".equals(type) ? completion(payload) : deactivation(payload);
        try {
            return new InboundEvent(uuid(event, "eventId"), type, 1, fixed[1], uuid(event, "aggregateId"),
                    uuid(metadata, "correlationId"), fact);
        } catch (IllegalArgumentException mismatch) {
            throw invalid();
        }
    }

    private static InboundEvent.Fact completion(JsonNode payload) {
        exactly(payload, COMPLETION);
        uniqueUuids(payload, "procedureIds");
        uniqueUuids(payload, "manualChargeIds");
        JsonNode version = payload.get("clinicalVersion");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() < 1) throw invalid();
        return new InboundEvent.CareCompletionDeclared(uuid(payload, "clinicalRecordId"),
                uuid(payload, "consultationId"), uuid(payload, "appointmentId"), uuid(payload, "patientId"),
                uuid(payload, "dentistId"), instant(payload, "completedAt"), version.asInt());
    }

    private static InboundEvent.Fact deactivation(JsonNode payload) {
        exactly(payload, DEACTIVATION);
        if (!payload.get("reasonCode").isTextual() || !REASONS.contains(payload.get("reasonCode").asText())) {
            throw invalid();
        }
        uuid(payload, "deactivatedBy");
        return new InboundEvent.PatientDeactivation(uuid(payload, "patientId"), instant(payload, "deactivatedAt"));
    }

    private static JsonNode exactly(JsonNode node, Set<String> fields) {
        if (node == null || !node.isObject() || node.size() != fields.size()) throw invalid();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            if (!fields.contains(names.next())) throw invalid();
        }
        return node;
    }

    private static void text(JsonNode node, String field, String expected) {
        if (!node.get(field).isTextual() || !expected.equals(node.get(field).asText())) throw invalid();
    }

    private static UUID uuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || !UUID_TEXT.matcher(value.asText()).matches()) throw invalid();
        return UUID.fromString(value.asText());
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw invalid();
        try {
            return OffsetDateTime.parse(value.asText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException notRfc3339) {
            throw invalid();
        }
    }

    private static void uniqueUuids(JsonNode node, String field) {
        JsonNode values = node.get(field);
        if (!values.isArray()) throw invalid();
        Set<UUID> seen = new HashSet<>();
        for (JsonNode value : values) {
            if (!value.isTextual() || !UUID_TEXT.matcher(value.asText()).matches()
                    || !seen.add(UUID.fromString(value.asText()))) {
                throw invalid();
            }
        }
    }

    private static InvalidEventException invalid() {
        return new InvalidEventException("INVALID_EVENT");
    }

    /** A message that can never be processed; it goes to the dead-letter queue. */
    public static final class InvalidEventException extends RuntimeException {
        private final String code;

        InvalidEventException(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
