package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.AppointmentResponse;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.LifecycleRequests;
import co.edu.corhuila.dlc.appointments.application.port.in.ChangeAppointmentStatus;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** HU-APT-002 state operations; every one answers 200 with the appointment's current representation. */
@RestController
public class AppointmentLifecycleController {
    private static final String ITEM = AppointmentController.COLLECTION + "/{id}";
    private static final String KEY = "Idempotency-Key";
    private final ChangeAppointmentStatus lifecycle;

    public AppointmentLifecycleController(ChangeAppointmentStatus lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @PostMapping(path = ITEM + "/confirmations", consumes = "application/json")
    public AppointmentResponse confirm(@PathVariable("id") UUID id,
            @RequestBody(required = false) LifecycleRequests.Confirm body,
            @RequestHeader(name = KEY, required = false) String key, HttpServletRequest request) {
        var valid = body == null ? new LifecycleRequests.Confirm(null, null) : body;
        return change(valid.missing(), key, () -> valid.toCommand(id), request);
    }

    @PostMapping(path = ITEM + "/cancellations", consumes = "application/json")
    public AppointmentResponse cancel(@PathVariable("id") UUID id,
            @RequestBody(required = false) LifecycleRequests.Cancel body,
            @RequestHeader(name = KEY, required = false) String key, HttpServletRequest request) {
        var valid = body == null ? new LifecycleRequests.Cancel(null, null, null) : body;
        return change(valid.missing(), key, () -> valid.toCommand(id), request);
    }

    @PostMapping(path = ITEM + "/reschedulings", consumes = "application/json")
    public AppointmentResponse reschedule(@PathVariable("id") UUID id,
            @RequestBody(required = false) LifecycleRequests.Reschedule body,
            @RequestHeader(name = KEY, required = false) String key, HttpServletRequest request) {
        var valid = body == null ? new LifecycleRequests.Reschedule(null, null, null, null, null) : body;
        return change(valid.missing(), key, () -> valid.toCommand(id), request);
    }

    @PostMapping(path = ITEM + "/attention-starts", consumes = "application/json")
    public AppointmentResponse startAttention(@PathVariable("id") UUID id,
            @RequestBody(required = false) LifecycleRequests.Version body,
            @RequestHeader(name = KEY, required = false) String key, HttpServletRequest request) {
        var valid = body == null ? new LifecycleRequests.Version(null) : body;
        return change(valid.missing(), key,
                () -> new LifecycleCommand.StartAttention(id, valid.expectedVersion()), request);
    }

    @PostMapping(path = ITEM + "/completions", consumes = "application/json")
    public AppointmentResponse complete(@PathVariable("id") UUID id,
            @RequestBody(required = false) LifecycleRequests.Version body,
            @RequestHeader(name = KEY, required = false) String key, HttpServletRequest request) {
        var valid = body == null ? new LifecycleRequests.Version(null) : body;
        return change(valid.missing(), key,
                () -> new LifecycleCommand.Complete(id, valid.expectedVersion()), request);
    }

    @PostMapping(path = ITEM + "/no-shows", consumes = "application/json")
    public AppointmentResponse noShow(@PathVariable("id") UUID id,
            @RequestBody(required = false) LifecycleRequests.NoShow body,
            @RequestHeader(name = KEY, required = false) String key, HttpServletRequest request) {
        var valid = body == null ? new LifecycleRequests.NoShow(null, null) : body;
        return change(valid.missing(), key, () -> valid.toCommand(id), request);
    }

    /** Every missing field and the header are reported together before the use case runs. */
    private AppointmentResponse change(List<ApiError.Detail> missing, String key,
            java.util.function.Supplier<LifecycleCommand> command, HttpServletRequest request) {
        var details = new ArrayList<>(missing);
        StaffRequests.checkIdempotencyKey(key, details);
        if (!details.isEmpty()) throw new RequestValidationException(details);
        return AppointmentResponse.from(lifecycle.change(command.get(), StaffRequests.context(request, key)));
    }
}
