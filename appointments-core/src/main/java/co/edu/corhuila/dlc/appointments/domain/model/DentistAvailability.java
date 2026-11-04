package co.edu.corhuila.dlc.appointments.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A dentist's bookable time: availability intervals minus blocked intervals, versioned for optimistic
 * replacement (contract 1.4.0, decisions G1-G2). Replacement is whole: it never edits single intervals.
 */
public final class DentistAvailability {
    private final UUID id;
    private final UUID dentistId;
    private final List<TimeInterval> intervals;
    private final List<TimeInterval> blockedIntervals;
    private final long version;
    private final Instant createdAt;
    private final UUID createdBy;
    private final Instant updatedAt;
    private final UUID updatedBy;

    private DentistAvailability(UUID id, UUID dentistId, List<TimeInterval> intervals,
            List<TimeInterval> blockedIntervals, long version, Instant createdAt, UUID createdBy, Instant updatedAt,
            UUID updatedBy) {
        this.id = id;
        this.dentistId = dentistId;
        this.intervals = List.copyOf(intervals);
        this.blockedIntervals = List.copyOf(blockedIntervals);
        this.version = version;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    /**
     * Validates the new intervals, then the version (the first replacement uses expectedVersion 1 and creates
     * version 1), then that every active appointment stays covered.
     */
    public static DentistAvailability replace(Optional<DentistAvailability> current, UUID dentistId,
            List<TimeInterval> intervals, List<TimeInterval> blockedIntervals, long expectedVersion,
            List<TimeInterval> activeAppointments, UUID actor, Instant now, UUID newId) {
        List<FieldError> errors = new ArrayList<>();
        check("intervals", intervals, errors);
        check("blockedIntervals", blockedIntervals, errors);
        if (!errors.isEmpty()) throw new InvalidAvailabilityException(errors);
        long currentVersion = current.map(DentistAvailability::version).orElse(1L);
        if (expectedVersion != currentVersion) throw new StaleVersionException();
        DentistAvailability next = current
                .map(c -> new DentistAvailability(c.id, dentistId, intervals, blockedIntervals, c.version + 1,
                        c.createdAt, c.createdBy, now, actor))
                .orElseGet(() -> new DentistAvailability(newId, dentistId, intervals, blockedIntervals, 1, now,
                        actor, now, actor));
        if (!activeAppointments.stream().allMatch(next::covers)) {
            throw new SchedulingException(SchedulingException.Reason.SLOT_UNAVAILABLE);
        }
        return next;
    }

    public static DentistAvailability reconstitute(UUID id, UUID dentistId, List<TimeInterval> intervals,
            List<TimeInterval> blockedIntervals, long version, Instant createdAt, UUID createdBy, Instant updatedAt,
            UUID updatedBy) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dentistId, "dentistId");
        if (version < 1) throw new IllegalStateException("Stored availability version is invalid.");
        return new DentistAvailability(id, dentistId, intervals, blockedIntervals, version, createdAt, createdBy,
                updatedAt, updatedBy);
    }

    /** True when one availability interval contains the range and no blocked interval overlaps it. */
    public boolean covers(TimeInterval range) {
        return intervals.stream().anyMatch(i -> i.contains(range))
                && blockedIntervals.stream().noneMatch(b -> b.overlaps(range));
    }

    /** Invalid ranges and overlaps inside one list are reported by their original position. */
    private static void check(String field, List<TimeInterval> list, List<FieldError> errors) {
        String[] problems = new String[list.size()];
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == null || !list.get(i).isValid()) {
                problems[i] = "Start must be before end.";
            } else {
                valid.add(i);
            }
        }
        valid.sort(Comparator.comparing(i -> list.get(i).start().toInstant()));
        for (int k = 1; k < valid.size(); k++) {
            if (list.get(valid.get(k - 1)).overlaps(list.get(valid.get(k)))) {
                problems[valid.get(k)] = "Overlaps another interval.";
            }
        }
        for (int i = 0; i < problems.length; i++) {
            if (problems[i] != null) errors.add(new FieldError(field + "[" + i + "]", problems[i]));
        }
    }

    public UUID id() { return id; }
    public UUID dentistId() { return dentistId; }
    public List<TimeInterval> intervals() { return intervals; }
    public List<TimeInterval> blockedIntervals() { return blockedIntervals; }
    public long version() { return version; }
    public Instant createdAt() { return createdAt; }
    public UUID createdBy() { return createdBy; }
    public Instant updatedAt() { return updatedAt; }
    public UUID updatedBy() { return updatedBy; }
}
