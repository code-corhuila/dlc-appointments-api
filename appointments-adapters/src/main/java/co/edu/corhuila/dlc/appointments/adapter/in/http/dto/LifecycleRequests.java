package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.domain.model.ConfirmationChannel;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Request bodies of the HU-APT-002 operations, one strict record per operation (additionalProperties: false).
 * {@code missing()} names every absent or out-of-range field; reason length stays a domain rule.
 */
public final class LifecycleRequests {
    private LifecycleRequests() { }

    public record Confirm(Long expectedVersion, ConfirmationChannel channel) {
        public List<ApiError.Detail> missing() {
            var details = version(expectedVersion);
            if (channel == null) details.add(new ApiError.Detail("channel", "Required: PHONE, IN_PERSON or EMAIL."));
            return details;
        }

        public LifecycleCommand toCommand(UUID id) {
            return new LifecycleCommand.Confirm(id, expectedVersion, channel);
        }
    }

    public record Cancel(Long expectedVersion, String reason, Boolean override) {
        public List<ApiError.Detail> missing() {
            return requireReason(version(expectedVersion), reason);
        }

        public LifecycleCommand toCommand(UUID id) {
            return new LifecycleCommand.Cancel(id, expectedVersion, reason, Boolean.TRUE.equals(override));
        }
    }

    public record Reschedule(Long expectedVersion, OffsetDateTime startAt, OffsetDateTime endAt, String reason,
            Boolean override) {
        public List<ApiError.Detail> missing() {
            var details = version(expectedVersion);
            if (startAt == null) details.add(new ApiError.Detail("startAt", "Required RFC 3339 timestamp with offset."));
            if (endAt == null) details.add(new ApiError.Detail("endAt", "Required RFC 3339 timestamp with offset."));
            return requireReason(details, reason);
        }

        public LifecycleCommand toCommand(UUID id) {
            return new LifecycleCommand.Reschedule(id, expectedVersion, startAt, endAt, reason,
                    Boolean.TRUE.equals(override));
        }
    }

    public record Version(Long expectedVersion) {
        public List<ApiError.Detail> missing() {
            return version(expectedVersion);
        }
    }

    public record NoShow(String reason, Long expectedVersion) {
        public List<ApiError.Detail> missing() {
            return requireReason(version(expectedVersion), reason);
        }

        public LifecycleCommand toCommand(UUID id) {
            return new LifecycleCommand.NoShow(id, expectedVersion, reason);
        }
    }

    private static List<ApiError.Detail> version(Long expectedVersion) {
        var details = new ArrayList<ApiError.Detail>();
        if (expectedVersion == null || expectedVersion < 1) {
            details.add(new ApiError.Detail("expectedVersion", "Required, an integer of at least 1."));
        }
        return details;
    }

    private static List<ApiError.Detail> requireReason(List<ApiError.Detail> details, String reason) {
        if (reason == null || reason.isBlank()) {
            details.add(new ApiError.Detail("reason", "Required, 1 to 1000 characters."));
        }
        return details;
    }
}
