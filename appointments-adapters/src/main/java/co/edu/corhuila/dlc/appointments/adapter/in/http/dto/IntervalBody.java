package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import co.edu.corhuila.dlc.appointments.domain.model.TimeInterval;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** One {startAt, endAt} item of the Availability contract; rendered in UTC.
 */
public record IntervalBody(OffsetDateTime startAt, OffsetDateTime endAt) {
    public TimeInterval toInterval() {
        return new TimeInterval(startAt, endAt);
    }

    public static IntervalBody from(TimeInterval interval) {
        return new IntervalBody(interval.start().withOffsetSameInstant(ZoneOffset.UTC),
                interval.end().withOffsetSameInstant(ZoneOffset.UTC));
    }
}
