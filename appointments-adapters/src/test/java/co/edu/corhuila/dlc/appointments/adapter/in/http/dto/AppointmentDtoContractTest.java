package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingResult;
import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointmentCommand;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.InvalidAppointmentException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppointmentDtoContractTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PATIENT = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID DENTIST = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final OffsetDateTime START = OffsetDateTime.parse("2026-11-01T09:00:00-05:00");
    private static final OffsetDateTime END = START.plusMinutes(30);
    // Explicit test configuration; this does not configure Spring MVC's production mapper.
    private final JsonMapper json = JsonMapper.builder().addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    void convertsExactlyTheFiveRequestFieldsWithoutChangingIdentifiersOffsetsOrReason() {
        var request = new CreateAppointmentRequest(PATIENT, DENTIST, START, END, "Revisi\u00f3n \uD83D\uDE00");
        var command = request.toCommand();
        assertEquals(PATIENT, command.patientId());
        assertEquals(DENTIST, command.dentistId());
        assertEquals(START, command.startAt());
        assertEquals(END, command.endAt());
        assertEquals(request.reason(), command.reason());
    }

    @Test
    void requestJsonHasOnlyTheFiveApprovedFieldsAndRoundTripsOffsetsAndUnicode() throws Exception {
        var request = new CreateAppointmentRequest(PATIENT, DENTIST, START, END, "\uD83D\uDE00".repeat(500));
        String encoded = json.writeValueAsString(request);
        JsonNode tree = json.readTree(encoded);
        assertEquals(Set.of("patientId", "dentistId", "startAt", "endAt", "reason"), fields(tree));
        assertEquals(PATIENT.toString(), tree.get("patientId").textValue());
        assertEquals(DENTIST.toString(), tree.get("dentistId").textValue());
        assertEquals(START, OffsetDateTime.parse(tree.get("startAt").textValue()));
        assertEquals(END, OffsetDateTime.parse(tree.get("endAt").textValue()));
        assertEquals(request, json.readValue(encoded, CreateAppointmentRequest.class));
    }

    @Test
    void strictTestMapperRejectsUnknownFieldsAndInvalidUuidOrDatesWithoutOffset() throws Exception {
        ObjectNode valid = json.valueToTree(new CreateAppointmentRequest(PATIENT, DENTIST, START, END, "Checkup"));
        assertThrows(UnrecognizedPropertyException.class, () -> json.treeToValue(
                valid.deepCopy().put("actorUserId", ID.toString()), CreateAppointmentRequest.class));
        assertThrows(InvalidFormatException.class, () -> json.treeToValue(
                valid.deepCopy().put("patientId", "invalid"), CreateAppointmentRequest.class));
        assertThrows(InvalidFormatException.class, () -> json.treeToValue(
                valid.deepCopy().put("startAt", "2026-11-01T09:00:00"), CreateAppointmentRequest.class));
    }

    @Test
    void conversionPreservesUnicodeBoundariesAndLeavesDomainValidationInTheCore() {
        for (int count : new int[]{1, 500}) {
            String reason = "\uD83D\uDE00".repeat(count);
            var command = new CreateAppointmentRequest(PATIENT, DENTIST, START, END, reason).toCommand();
            assertEquals(reason, appointment(command).reason());
        }
        for (String reason : new String[]{"", " ", "\uD83D\uDE00".repeat(501)}) {
            var command = new CreateAppointmentRequest(PATIENT, DENTIST, START, END, reason).toCommand();
            var error = assertThrows(InvalidAppointmentException.class, () -> appointment(command));
            assertEquals("reason", error.field());
        }
    }

    @Test
    void missingRequestValuesAreNotValidatedByJsonParsingAlone() throws Exception {
        var request = json.readValue("{}", CreateAppointmentRequest.class);
        assertNull(request.patientId());
        var error = assertThrows(InvalidAppointmentException.class, () -> appointment(request.toCommand()));
        assertEquals("patientId", error.field());
    }

    @Test
    void responseExplicitlyMapsAuthoritativeAppointmentAndOriginalVersion() {
        var appointment = Appointment.schedule(ID, PATIENT, DENTIST, START, END, "Checkup");
        var response = AppointmentResponse.from(new SchedulingResult(appointment, 7));
        assertEquals(ID, response.id());
        assertEquals(PATIENT, response.patientId());
        assertEquals(DENTIST, response.dentistId());
        assertEquals(START, response.startAt());
        assertEquals(END, response.endAt());
        assertEquals("PROGRAMADA", response.status());
        assertEquals(7, response.version());
        assertEquals(appointment.reason(), response.reason());
        assertEquals(appointment.reminderDueAt(), response.reminderDueAt());
    }

    @Test
    void responseJsonIsFlatAndContainsOnlyRequiredAndDerivableFields() throws Exception {
        var appointment = Appointment.schedule(ID, PATIENT, DENTIST, START, END, "Checkup");
        JsonNode tree = json.readTree(json.writeValueAsString(AppointmentResponse.from(new SchedulingResult(appointment, 7))));
        assertEquals(Set.of("id", "patientId", "dentistId", "startAt", "endAt", "status", "version",
                "reason", "reminderDueAt"), fields(tree));
        assertEquals(ID.toString(), tree.get("id").textValue());
        assertEquals("PROGRAMADA", tree.get("status").textValue());
        assertTrue(tree.get("version").isIntegralNumber());
        assertEquals(7, tree.get("version").longValue());
        assertEquals(START, OffsetDateTime.parse(tree.get("startAt").textValue()));
        assertEquals(END, OffsetDateTime.parse(tree.get("endAt").textValue()));
        assertEquals(START.minusHours(24), OffsetDateTime.parse(tree.get("reminderDueAt").textValue()));
        tree.elements().forEachRemaining(value -> assertFalse(value.isNull()));
    }

    private Appointment appointment(ScheduleAppointmentCommand command) {
        return Appointment.schedule(ID, command.patientId(), command.dentistId(),
                command.startAt(), command.endAt(), command.reason());
    }

    private Set<String> fields(JsonNode tree) {
        Set<String> names = new HashSet<>();
        tree.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
