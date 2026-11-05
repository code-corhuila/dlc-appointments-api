package co.edu.corhuila.dlc.appointments.domain.model;

import java.time.OffsetDateTime;

/** Half-open [start, end) instant range; validity is checked by the aggregate that receives it. */
public record TimeInterval(OffsetDateTime start, OffsetDateTime end) {
    public boolean isValid() {
        return start != null && end != null && start.toInstant().isBefore(end.toInstant());
    }

    public boolean overlaps(TimeInterval other) {
        return start.toInstant().isBefore(other.end.toInstant()) && other.start.toInstant().isBefore(end.toInstant());
    }

    public boolean contains(TimeInterval other) {
        return !start.toInstant().isAfter(other.start.toInstant()) && !end.toInstant().isBefore(other.end.toInstant());
    }
}
