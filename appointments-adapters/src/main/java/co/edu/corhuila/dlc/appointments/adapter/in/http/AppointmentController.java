package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.AppointmentPageResponse;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.AppointmentResponse;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.CreateAppointmentRequest;
import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.port.in.ListAppointments;
import co.edu.corhuila.dlc.appointments.application.port.in.ReadAppointment;
import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointment;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Appointment public operations; authentication already ran in StaffAuthenticationFilter. */
@RestController
public class AppointmentController {
    static final String COLLECTION = "/api/v1/appointments";
    private final ScheduleAppointment scheduling;
    private final ReadAppointment reading;
    private final ListAppointments listing;

    public AppointmentController(ScheduleAppointment scheduling, ReadAppointment reading, ListAppointments listing) {
        this.scheduling = Objects.requireNonNull(scheduling, "scheduling");
        this.reading = Objects.requireNonNull(reading, "reading");
        this.listing = Objects.requireNonNull(listing, "listing");
    }

    /** HU-APT-003 calendar: bounded, most recent first, within the reader's scope. */
    @GetMapping(COLLECTION)
    public AppointmentPageResponse list(HttpServletRequest request) {
        var query = CalendarQueryParser.parse(request.getParameterMap());
        var staff = StaffRequests.staff(request);
        return AppointmentPageResponse.from(listing.list(query, new ReaderContext(staff.userId(), staff.roles())));
    }

    /** HU-APT-003: 404 for a missing appointment and for one outside the reader's scope alike. */
    @GetMapping(COLLECTION + "/{id}")
    public AppointmentResponse read(@PathVariable("id") UUID id, HttpServletRequest request) {
        var staff = StaffRequests.staff(request);
        return AppointmentResponse.from(reading.read(id, new ReaderContext(staff.userId(), staff.roles())));
    }

    /** 201 with Location for a new appointment; 200 with the same appointment for an idempotent retry. */
    @PostMapping(path = COLLECTION, consumes = "application/json")
    public ResponseEntity<AppointmentResponse> schedule(@RequestBody(required = false) CreateAppointmentRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        validate(body, idempotencyKey);
        var result = scheduling.schedule(body.toCommand(), StaffRequests.context(request, idempotencyKey));
        var response = AppointmentResponse.from(result);
        return result.created()
                ? ResponseEntity.created(URI.create(COLLECTION + "/" + response.id())).body(response)
                : ResponseEntity.ok(response);
    }

    /** Reports every missing field and the header together; value rules stay in the domain. */
    private static void validate(CreateAppointmentRequest body, String idempotencyKey) {
        List<ApiError.Detail> details = new ArrayList<>();
        if (body == null || body.patientId() == null) details.add(required("patientId"));
        if (body == null || body.dentistId() == null) details.add(required("dentistId"));
        if (body == null || body.startAt() == null) details.add(required("startAt"));
        if (body == null || body.endAt() == null) details.add(required("endAt"));
        if (body == null || body.reason() == null || body.reason().isBlank()) {
            details.add(new ApiError.Detail("reason", "Required, 1 to 500 characters."));
        }
        StaffRequests.checkIdempotencyKey(idempotencyKey, details);
        if (!details.isEmpty()) throw new RequestValidationException(details);
    }

    private static ApiError.Detail required(String field) {
        return new ApiError.Detail(field, "Required value is missing or invalid.");
    }
}
