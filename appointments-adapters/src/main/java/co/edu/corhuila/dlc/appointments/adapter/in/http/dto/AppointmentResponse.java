package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingResult;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** The contract Appointment representation; the aggregate is never serialized directly (Norma 5.3.4).
 * Every instant is rendered in UTC (Norma 5.3.5).
 */
public record AppointmentResponse(UUID id, UUID patientId, UUID dentistId, OffsetDateTime startAt,
        OffsetDateTime endAt, String reason, String status, String confirmationStatus, OffsetDateTime reminderDueAt,
        Instant createdAt, Instant updatedAt, long version) {

    public static AppointmentResponse from(SchedulingResult result) {
        return from(result.appointment());
    }

    public static AppointmentResponse from(Appointment a) {
        return new AppointmentResponse(a.id(), a.patientId(), a.dentistId(), utc(a.startAt()), utc(a.endAt()),
                a.reason(), a.status().name(), a.confirmationStatus().name(), utc(a.reminderDueAt()),
                a.createdAt(), a.updatedAt(), a.version());
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC);
    }
}
