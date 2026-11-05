package co.edu.corhuila.dlc.appointments.application.port.in;

import java.time.OffsetDateTime;
import java.util.UUID;

/** CreateAppointment body; the appointment identifier is generated internally. */
public record ScheduleAppointmentCommand(UUID patientId, UUID dentistId,
        OffsetDateTime startAt, OffsetDateTime endAt, String reason) { }
