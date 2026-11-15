package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.AssignmentRequests;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ClinicalAssignmentPageResponse;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ClinicalAssignmentResponse;
import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.usecase.ClinicalAssignmentsUseCase;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** HU-APT-004 ongoing clinical assignments: metadata only, Administrator and Secretary/Assistant only. */
@RestController
public class ClinicalAssignmentController {
    static final String COLLECTION = "/api/v1/clinical-assignments";
    private static final String KEY = "Idempotency-Key";
    private final ClinicalAssignmentsUseCase assignments;

    public ClinicalAssignmentController(ClinicalAssignmentsUseCase assignments) {
        this.assignments = Objects.requireNonNull(assignments, "assignments");
    }

    @GetMapping(COLLECTION)
    public ClinicalAssignmentPageResponse list(HttpServletRequest request) {
        var query = CalendarQueryParser.assignments(request.getParameterMap());
        return ClinicalAssignmentPageResponse.from(assignments.list(query, reader(request)));
    }

    @GetMapping(COLLECTION + "/{id}")
    public ClinicalAssignmentResponse read(@PathVariable("id") UUID id, HttpServletRequest request) {
        return ClinicalAssignmentResponse.from(assignments.read(id, reader(request)));
    }

    /** 201 with Location for a new assignment; 200 with the original one for an idempotent retry. */
    @PostMapping(path = COLLECTION, consumes = "application/json")
    public ResponseEntity<ClinicalAssignmentResponse> start(
            @RequestBody(required = false) AssignmentRequests.Start body,
            @RequestHeader(name = KEY, required = false) String key, HttpServletRequest request) {
        var valid = body == null ? new AssignmentRequests.Start(null, null, null) : body;
        var details = new ArrayList<>(valid.missing());
        StaffRequests.checkIdempotencyKey(key, details);
        if (!details.isEmpty()) throw new RequestValidationException(details);
        var result = assignments.start(valid.patientId(), valid.dentistId(), valid.reason(),
                StaffRequests.context(request, key));
        var response = ClinicalAssignmentResponse.from(result.assignment());
        return result.created()
                ? ResponseEntity.created(URI.create(COLLECTION + "/" + response.id())).body(response)
                : ResponseEntity.ok(response);
    }

    @PostMapping(path = COLLECTION + "/{id}/endings", consumes = "application/json")
    public ClinicalAssignmentResponse end(@PathVariable("id") UUID id,
            @RequestBody(required = false) AssignmentRequests.End body,
            @RequestHeader(name = KEY, required = false) String key, HttpServletRequest request) {
        var valid = body == null ? new AssignmentRequests.End(null, null) : body;
        var details = new ArrayList<>(valid.missing());
        StaffRequests.checkIdempotencyKey(key, details);
        if (!details.isEmpty()) throw new RequestValidationException(details);
        return ClinicalAssignmentResponse.from(assignments.end(id, valid.expectedVersion(), valid.reason(),
                StaffRequests.context(request, key)));
    }

    private static ReaderContext reader(HttpServletRequest request) {
        var staff = StaffRequests.staff(request);
        return new ReaderContext(staff.userId(), staff.roles());
    }
}
