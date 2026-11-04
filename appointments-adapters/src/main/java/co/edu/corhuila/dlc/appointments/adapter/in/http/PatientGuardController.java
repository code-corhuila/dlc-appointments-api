package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.AuthenticatedService;
import co.edu.corhuila.dlc.appointments.application.usecase.PatientGuards;
import co.edu.corhuila.dlc.appointments.domain.model.GuardKind;
import co.edu.corhuila.dlc.appointments.domain.model.GuardState;
import co.edu.corhuila.dlc.appointments.domain.model.PatientGuard;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * #17 ADR-017 guard operations (internal-coordination.yaml), scope patient-gates:coordinate. The verified
 * service subject is the guard's caller; another service reading or resolving it gets 404.
 */
@RestController
public class PatientGuardController {
    private static final String SCOPE = "patient-gates:coordinate";
    private final PatientGuards guards;

    public PatientGuardController(PatientGuards guards) {
        this.guards = Objects.requireNonNull(guards, "guards");
    }

    public record Acquisition(UUID operationId) { }

    public record Resolution(String token, String outcome) { }

    public record GuardStatus(UUID id, UUID patientId, UUID operationId, String state) {
        static GuardStatus from(PatientGuard g) {
            return new GuardStatus(g.id(), g.patientId(), g.operationId(), g.state().name());
        }
    }

    public record AcquiredGuard(UUID id, UUID patientId, UUID operationId, String state, String token) { }

    @PostMapping(path = "/internal/v1/patients/{id}/deactivation-guards", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public AcquiredGuard deactivation(@PathVariable("id") UUID patientId,
            @RequestBody(required = false) Acquisition body, HttpServletRequest request) {
        return acquire(patientId, body, request, GuardKind.DEACTIVATION);
    }

    @PostMapping(path = "/internal/v1/patients/{id}/activity-guards", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    public AcquiredGuard activity(@PathVariable("id") UUID patientId,
            @RequestBody(required = false) Acquisition body, HttpServletRequest request) {
        return acquire(patientId, body, request, GuardKind.ACTIVITY);
    }

    @GetMapping("/internal/v1/patient-guards")
    public GuardStatus byOperation(HttpServletRequest request) {
        String caller = caller(request);
        Map<String, String[]> parameters = request.getParameterMap();
        String[] values = parameters.get("operationId");
        UUID operation = null;
        try {
            if (values != null && values.length == 1 && parameters.size() == 1) operation = UUID.fromString(values[0]);
        } catch (IllegalArgumentException invalid) {
            operation = null;
        }
        if (operation == null) throw invalid("operationId", "Exactly one UUID is required.");
        return GuardStatus.from(guards.byOperation(operation, caller));
    }

    @GetMapping("/internal/v1/patient-guards/{id}")
    public GuardStatus byId(@PathVariable("id") UUID guardId, HttpServletRequest request) {
        return GuardStatus.from(guards.byId(guardId, caller(request)));
    }

    @PostMapping(path = "/internal/v1/patient-guards/{id}/resolutions", consumes = "application/json")
    public GuardStatus resolve(@PathVariable("id") UUID guardId, @RequestBody(required = false) Resolution body,
            HttpServletRequest request) {
        String caller = caller(request);
        if (body == null || body.token() == null || body.token().isBlank()) throw invalid("token", "Required.");
        if (!"FINALIZED".equals(body.outcome()) && !"ABORTED".equals(body.outcome())) {
            throw invalid("outcome", "Must be FINALIZED or ABORTED.");
        }
        return GuardStatus.from(guards.resolve(guardId, caller, body.token(), GuardState.valueOf(body.outcome())));
    }

    private AcquiredGuard acquire(UUID patientId, Acquisition body, HttpServletRequest request, GuardKind kind) {
        String caller = caller(request);
        if (body == null || body.operationId() == null) throw invalid("operationId", "Required UUID.");
        var acquired = guards.acquire(patientId, body.operationId(), caller, kind);
        PatientGuard g = acquired.guard();
        return new AcquiredGuard(g.id(), g.patientId(), g.operationId(), g.state().name(), acquired.token());
    }

    /** The scope is required and the subject must be a lowercase service name (it is stored as caller_service). */
    private static String caller(HttpServletRequest request) {
        var service = (AuthenticatedService) request.getAttribute(AuthenticatedService.ATTRIBUTE);
        if (service == null || !service.hasScope(SCOPE) || !service.subject().matches("[a-z][a-z-]{2,63}")) {
            throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
        }
        return service.subject();
    }

    private static RequestValidationException invalid(String field, String message) {
        return new RequestValidationException(List.of(new ApiError.Detail(field, message)));
    }
}
