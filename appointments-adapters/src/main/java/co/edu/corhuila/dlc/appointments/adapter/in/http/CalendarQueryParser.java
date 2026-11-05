package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.application.model.AppointmentQuery;
import co.edu.corhuila.dlc.appointments.application.model.AssignmentQuery;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * GET /api/v1/appointments query parameters (Norma 5.3.6, API-APT-01): page from 1, limit 1 to 100 (default 20),
 * optional patientId/dentistId, and from/to instants with an explicit offset where from <= to. Every invalid or
 * unknown parameter is reported together as a 400 VALIDATION_ERROR detail.
 */
final class CalendarQueryParser {
    private static final Set<String> KNOWN = Set.of("page", "limit", "patientId", "dentistId", "from", "to");
    private static final Set<String> ASSIGNMENT_FILTERS = Set.of("page", "limit", "patientId", "dentistId");

    private CalendarQueryParser() { }

    static AppointmentQuery parse(Map<String, String[]> parameters) {
        List<ApiError.Detail> details = new ArrayList<>();
        parameters.keySet().stream().filter(name -> !KNOWN.contains(name)).sorted()
                .forEach(name -> details.add(new ApiError.Detail(name, "Unknown filter.")));
        int page = integer(parameters, "page", 1, 1, Integer.MAX_VALUE, details);
        int limit = integer(parameters, "limit", 20, 1, 100, details);
        UUID patient = uuid(parameters, "patientId", details);
        UUID dentist = uuid(parameters, "dentistId", details);
        OffsetDateTime from = instant(parameters, "from", details);
        OffsetDateTime to = instant(parameters, "to", details);
        if (from != null && to != null && from.isAfter(to)) {
            details.add(new ApiError.Detail("to", "Must not be before from."));
        }
        if (!details.isEmpty()) throw new RequestValidationException(details);
        return new AppointmentQuery(patient, dentist, from, to, page, limit);
    }

    /** GET /api/v1/clinical-assignments: page, limit and optional patientId/dentistId only. */
    static AssignmentQuery assignments(Map<String, String[]> parameters) {
        List<ApiError.Detail> details = new ArrayList<>();
        parameters.keySet().stream().filter(name -> !ASSIGNMENT_FILTERS.contains(name)).sorted()
                .forEach(name -> details.add(new ApiError.Detail(name, "Unknown filter.")));
        int page = integer(parameters, "page", 1, 1, Integer.MAX_VALUE, details);
        int limit = integer(parameters, "limit", 20, 1, 100, details);
        UUID patient = uuid(parameters, "patientId", details);
        UUID dentist = uuid(parameters, "dentistId", details);
        if (!details.isEmpty()) throw new RequestValidationException(details);
        return new AssignmentQuery(page, limit, Optional.ofNullable(patient), Optional.ofNullable(dentist));
    }

    private static String single(Map<String, String[]> parameters, String name, List<ApiError.Detail> details) {
        String[] values = parameters.get(name);
        if (values == null) return null;
        if (values.length != 1 || values[0].isBlank()) {
            details.add(new ApiError.Detail(name, "Exactly one non-empty value is required."));
            return null;
        }
        return values[0];
    }

    private static int integer(Map<String, String[]> parameters, String name, int fallback, int min, int max,
            List<ApiError.Detail> details) {
        String value = single(parameters, name, details);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed >= min && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        details.add(new ApiError.Detail(name, "Must be an integer from " + min + " to " + max + "."));
        return fallback;
    }

    private static UUID uuid(Map<String, String[]> parameters, String name, List<ApiError.Detail> details) {
        String value = single(parameters, name, details);
        if (value == null) return null;
        try {
            UUID parsed = UUID.fromString(value);
            if (parsed.toString().equalsIgnoreCase(value)) return parsed;
        } catch (IllegalArgumentException ignored) {
            // reported below
        }
        details.add(new ApiError.Detail(name, "Must be a UUID."));
        return null;
    }

    private static OffsetDateTime instant(Map<String, String[]> parameters, String name,
            List<ApiError.Detail> details) {
        String value = single(parameters, name, details);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException invalid) {
            details.add(new ApiError.Detail(name, "Must be an RFC 3339 instant with an explicit offset."));
            return null;
        }
    }
}
