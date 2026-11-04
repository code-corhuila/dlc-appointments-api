package co.edu.corhuila.dlc.appointments.application.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Calendar request already validated at the boundary: optional filters, from <= startAt < to, page >= 1. */
public record AppointmentQuery(UUID patientId, UUID dentistId, OffsetDateTime from, OffsetDateTime to, int page,
        int limit) { }
