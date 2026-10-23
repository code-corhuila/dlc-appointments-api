package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingResult;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Flat creation response; optional fields are included only when provided by the core. */
public record AppointmentResponse(UUID id, UUID patientId, UUID dentistId,
        OffsetDateTime startAt, OffsetDateTime endAt, String status, long version,
        String reason, OffsetDateTime reminderDueAt) {
    public static AppointmentResponse from(SchedulingResult result) {
        var appointment = result.appointment();
        return new AppointmentResponse(appointment.id(), appointment.patientId(), appointment.dentistId(),
                appointment.startAt(), appointment.endAt(), appointment.status().name(), result.version(),
                appointment.reason(), appointment.reminderDueAt());
    }
}
