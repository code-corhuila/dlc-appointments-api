package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import co.edu.corhuila.dlc.appointments.domain.model.DentistAvailability;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contract Availability representation; the aggregate is never serialized directly. */
public record AvailabilityResponse(UUID id, UUID dentistId, List<IntervalBody> intervals,
        List<IntervalBody> blockedIntervals, Instant createdAt, Instant updatedAt, long version) {
    public static AvailabilityResponse from(DentistAvailability a) {
        return new AvailabilityResponse(a.id(), a.dentistId(), a.intervals().stream().map(IntervalBody::from).toList(),
                a.blockedIntervals().stream().map(IntervalBody::from).toList(), a.createdAt(), a.updatedAt(),
                a.version());
    }
}
