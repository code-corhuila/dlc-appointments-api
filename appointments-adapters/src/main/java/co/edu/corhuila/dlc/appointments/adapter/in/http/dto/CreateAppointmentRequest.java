package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointmentCommand;
import java.time.OffsetDateTime;
import java.util.UUID;

/** CreateAppointment transport fields only. Domain validation runs through the use case.
 * JSON strictness and offset preservation must be configured in the future HTTP adapter.
 */
public record CreateAppointmentRequest(UUID patientId, UUID dentistId,
        OffsetDateTime startAt, OffsetDateTime endAt, String reason,
        @JsonInclude(JsonInclude.Include.NON_NULL) String typeCode) {
    /** Without an appointment type (HU-APT-006 is optional). */
    public CreateAppointmentRequest(UUID patientId, UUID dentistId, OffsetDateTime startAt, OffsetDateTime endAt,
            String reason) {
        this(patientId, dentistId, startAt, endAt, reason, null);
    }

    public ScheduleAppointmentCommand toCommand() {
        return new ScheduleAppointmentCommand(patientId, dentistId, startAt, endAt, reason, typeCode);
    }
}
