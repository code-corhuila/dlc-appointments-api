package co.edu.corhuila.dlc.appointments.application.port.in;

import co.edu.corhuila.dlc.appointments.domain.model.TimeInterval;
import java.util.List;
import java.util.UUID;

/** PUT /dentists/{id}/availability body: whole replacement guarded by expectedVersion. */
public record ReplaceAvailabilityCommand(UUID dentistId, List<TimeInterval> intervals,
        List<TimeInterval> blockedIntervals, long expectedVersion) { }
