package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.AppointmentPageResponse;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.AuthenticatedService;
import co.edu.corhuila.dlc.appointments.application.model.CareRelationship;
import co.edu.corhuila.dlc.appointments.application.usecase.InternalRelationshipReads;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** #16 private reads owned by Appointments (internal-coordination.yaml); identifiers only, no profile data. */
@RestController
public class InternalReadController {
    private static final String ASSIGNMENTS_READ = "assignments:read";
    private static final String ENCOUNTER_READ = "appointments:encounter-status:read";
    private final InternalRelationshipReads reads;

    public InternalReadController(InternalRelationshipReads reads) {
        this.reads = Objects.requireNonNull(reads, "reads");
    }

    public record Relationship(UUID patientId, UUID dentistId, boolean assigned, long relationshipVersion,
            List<String> sourceTypes) {
        static Relationship from(CareRelationship r) {
            return new Relationship(r.patientId(), r.dentistId(), r.assigned(), r.version(), r.sourceTypes());
        }
    }

    public record AssignedPatient(UUID patientId, long relationshipVersion, List<String> sourceTypes) { }

    public record AssignedPatients(UUID dentistId, long assignmentsVersion, List<AssignedPatient> data,
            AppointmentPageResponse.Meta meta) { }

    public record EncounterStatus(UUID appointmentId, UUID patientId, UUID dentistId, String status, long version) {
        static EncounterStatus from(Appointment a) {
            return new EncounterStatus(a.id(), a.patientId(), a.dentistId(), a.status().name(), a.version());
        }
    }

    @GetMapping("/internal/v1/patients/{id}/assignments")
    public Relationship relationship(@PathVariable("id") UUID patientId, HttpServletRequest request) {
        requireScope(request, ASSIGNMENTS_READ);
        Map<String, String[]> parameters = request.getParameterMap();
        String[] dentist = parameters.get("dentistId");
        UUID dentistId = null;
        try {
            if (dentist != null && dentist.length == 1) dentistId = UUID.fromString(dentist[0]);
        } catch (IllegalArgumentException invalid) {
            dentistId = null;
        }
        if (dentistId == null || parameters.size() != 1) {
            throw new RequestValidationException(List.of(new ApiError.Detail("dentistId", "Exactly one UUID is required.")));
        }
        return Relationship.from(reads.relationship(patientId, dentistId));
    }

    @GetMapping("/internal/v1/dentists/{id}/assigned-patients")
    public AssignedPatients assignedPatients(@PathVariable("id") UUID dentistId, HttpServletRequest request) {
        requireScope(request, ASSIGNMENTS_READ);
        var query = CalendarQueryParser.assignments(request.getParameterMap());
        if (query.patientId().isPresent() || query.dentistId().isPresent()) {
            throw new RequestValidationException(List.of(new ApiError.Detail(
                    query.patientId().isPresent() ? "patientId" : "dentistId", "Unknown filter.")));
        }
        var page = reads.assignedPatients(dentistId, query.page(), query.limit());
        return new AssignedPatients(dentistId, page.assignmentsVersion(), page.items().stream()
                .map(r -> new AssignedPatient(r.patientId(), r.version(), r.sourceTypes())).toList(),
                new AppointmentPageResponse.Meta(page.page(), page.limit(), page.total(), page.totalPages()));
    }

    @GetMapping("/internal/v1/appointments/{id}/encounter-status")
    public EncounterStatus encounterStatus(@PathVariable("id") UUID appointmentId, HttpServletRequest request) {
        requireScope(request, ENCOUNTER_READ);
        return EncounterStatus.from(reads.encounterStatus(appointmentId));
    }

    private static void requireScope(HttpServletRequest request, String scope) {
        var service = (AuthenticatedService) request.getAttribute(AuthenticatedService.ATTRIBUTE);
        if (service == null || !service.hasScope(scope)) {
            throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
        }
    }
}
