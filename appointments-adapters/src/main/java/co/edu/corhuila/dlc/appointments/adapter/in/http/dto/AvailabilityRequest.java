package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import java.util.List;

/** PUT /dentists/{id}/availability body: whole replacement guarded by expectedVersion. */
public record AvailabilityRequest(List<IntervalBody> intervals, List<IntervalBody> blockedIntervals,
        Long expectedVersion) { }
