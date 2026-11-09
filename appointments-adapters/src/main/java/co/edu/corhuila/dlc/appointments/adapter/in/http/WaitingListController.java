package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.model.WaitingListEntry;
import co.edu.corhuila.dlc.appointments.application.port.out.WaitingListStore;
import co.edu.corhuila.dlc.appointments.application.usecase.WaitingListUseCase;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** HU-APT-005 waiting list: Administrator and Secretary/Assistant only; oldest entries first. */
@RestController
public class WaitingListController {
    static final String COLLECTION = "/api/v1/waiting-list-entries";
    private static final String KEY = "Idempotency-Key";
    private static final Set<String> FILTERS = Set.of("page", "limit", "status");
    private final WaitingListUseCase waitingList;

    public WaitingListController(WaitingListUseCase waitingList) {
        this.waitingList = Objects.requireNonNull(waitingList, "waitingList");
    }

    @GetMapping(COLLECTION)
    public Map<String, Object> list(HttpServletRequest request) {
        var parameters = request.getParameterMap();
        var details = new ArrayList<ApiError.Detail>();
        parameters.keySet().stream().filter(name -> !FILTERS.contains(name)).sorted()
                .forEach(name -> details.add(new ApiError.Detail(name, "Unknown filter.")));
        int page = number(parameters, "page", 1, 100_000, 1, details);
        int limit = number(parameters, "limit", 1, 100, 20, details);
        WaitingListEntry.Status status = WaitingListEntry.Status.WAITING;
        String[] raw = parameters.get("status");
        if (raw != null) {
            try {
                status = WaitingListEntry.Status.valueOf(raw.length == 1 ? raw[0] : "");
            } catch (IllegalArgumentException invalid) {
                details.add(new ApiError.Detail("status", "One of WAITING, SCHEDULED or WITHDRAWN."));
            }
        }
        if (!details.isEmpty()) throw new RequestValidationException(details);
        var staff = StaffRequests.staff(request);
        WaitingListStore.Page result = waitingList.list(status, page, limit,
                new ReaderContext(staff.userId(), staff.roles()));
        long pages = result.total() == 0 ? 0 : (result.total() + limit - 1) / limit;
        return Map.of("data", result.data().stream().map(Entry::from).toList(),
                "meta", Map.of("page", page, "limit", limit, "total", result.total(), "totalPages", pages));
    }

    /** 201 for a new entry; 200 with the original one for an idempotent retry. */
    @PostMapping(path = COLLECTION, consumes = "application/json")
    public ResponseEntity<Entry> create(@RequestBody(required = false) Create body,
            @RequestHeader(name = KEY, required = false) String key, HttpServletRequest request) {
        var valid = body == null ? new Create(null, null, null, null, null) : body;
        var details = new ArrayList<ApiError.Detail>();
        if (valid.patientId() == null) details.add(new ApiError.Detail("patientId", "Required UUID."));
        if (valid.reason() == null || valid.reason().isBlank()) {
            details.add(new ApiError.Detail("reason", "Required, 1 to 500 characters."));
        }
        StaffRequests.checkIdempotencyKey(key, details);
        if (!details.isEmpty()) throw new RequestValidationException(details);
        var result = waitingList.create(valid.patientId(), valid.dentistId(), valid.reason(), valid.preferredFrom(),
                valid.preferredTo(), StaffRequests.context(request, key));
        var entry = Entry.from(result.entry());
        return result.created()
                ? ResponseEntity.created(java.net.URI.create(COLLECTION + "/" + entry.id())).body(entry)
                : ResponseEntity.ok(entry);
    }

    @PostMapping(path = COLLECTION + "/{id}/resolutions", consumes = "application/json")
    public Entry resolve(@PathVariable("id") UUID id, @RequestBody(required = false) Resolve body,
            @RequestHeader(name = KEY, required = false) String key, HttpServletRequest request) {
        var valid = body == null ? new Resolve(null, null, null) : body;
        var details = new ArrayList<ApiError.Detail>();
        WaitingListEntry.Status outcome = null;
        if ("SCHEDULED".equals(valid.outcome()) || "WITHDRAWN".equals(valid.outcome())) {
            outcome = WaitingListEntry.Status.valueOf(valid.outcome());
        } else {
            details.add(new ApiError.Detail("outcome", "One of SCHEDULED or WITHDRAWN."));
        }
        if (valid.expectedVersion() == null || valid.expectedVersion() < 1) {
            details.add(new ApiError.Detail("expectedVersion", "Required, an integer of at least 1."));
        }
        StaffRequests.checkIdempotencyKey(key, details);
        if (!details.isEmpty()) throw new RequestValidationException(details);
        return Entry.from(waitingList.resolve(id, outcome, valid.appointmentId(), valid.expectedVersion(),
                StaffRequests.context(request, key)));
    }

    private static int number(Map<String, String[]> parameters, String name, int min, int max, int fallback,
            List<ApiError.Detail> details) {
        String[] values = parameters.get(name);
        if (values == null) return fallback;
        try {
            int value = Integer.parseInt(values.length == 1 ? values[0] : "");
            if (value >= min && value <= max) return value;
        } catch (NumberFormatException invalid) {
            // reported below
        }
        details.add(new ApiError.Detail(name, "An integer from " + min + " to " + max + "."));
        return fallback;
    }

    public record Create(UUID patientId, UUID dentistId, String reason, LocalDate preferredFrom,
            LocalDate preferredTo) { }

    public record Resolve(String outcome, UUID appointmentId, Long expectedVersion) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(UUID id, UUID patientId, UUID dentistId, String reason, LocalDate preferredFrom,
            LocalDate preferredTo, String status, UUID appointmentId, Instant createdAt, UUID createdBy,
            Instant resolvedAt, UUID resolvedBy, long version) {
        static Entry from(WaitingListEntry e) {
            return new Entry(e.id(), e.patientId(), e.dentistId(), e.reason(), e.preferredFrom(), e.preferredTo(),
                    e.status().name(), e.appointmentId(), e.createdAt(), e.createdBy(), e.resolvedAt(),
                    e.resolvedBy(), e.version());
        }
    }
}
