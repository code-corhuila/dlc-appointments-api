package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.AvailabilityRequest;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.AvailabilityResponse;
import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.IntervalBody;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.AuthenticatedStaff;
import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.port.in.ReadDentistAvailability;
import co.edu.corhuila.dlc.appointments.application.port.in.ReplaceAvailabilityCommand;
import co.edu.corhuila.dlc.appointments.application.port.in.ReplaceDentistAvailability;
import co.edu.corhuila.dlc.appointments.domain.model.TimeInterval;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** HU-APT-001 dentist availability (contract 1.4.0); authentication already ran in the filter chain. */
@RestController
public class AvailabilityController {
    private static final String PATH = "/api/v1/dentists/{id}/availability";
    private final ReadDentistAvailability reading;
    private final ReplaceDentistAvailability replacing;

    public AvailabilityController(ReadDentistAvailability reading, ReplaceDentistAvailability replacing) {
        this.reading = Objects.requireNonNull(reading, "reading");
        this.replacing = Objects.requireNonNull(replacing, "replacing");
    }

    @GetMapping(PATH)
    public AvailabilityResponse read(@PathVariable("id") UUID dentistId, HttpServletRequest request) {
        return AvailabilityResponse.from(reading.read(dentistId, reader(request)));
    }

    @PutMapping(path = PATH, consumes = "application/json")
    public AvailabilityResponse replace(@PathVariable("id") UUID dentistId,
            @RequestBody(required = false) AvailabilityRequest body, HttpServletRequest request) {
        validate(body);
        var command = new ReplaceAvailabilityCommand(dentistId, intervals(body.intervals()),
                intervals(body.blockedIntervals()), body.expectedVersion());
        return AvailabilityResponse.from(replacing.replace(command, reader(request)));
    }

    /** Presence and range of the body fields; interval rules belong to the domain. */
    private static void validate(AvailabilityRequest body) {
        List<ApiError.Detail> details = new ArrayList<>();
        if (body == null || body.intervals() == null) details.add(required("intervals"));
        if (body == null || body.blockedIntervals() == null) details.add(required("blockedIntervals"));
        if (body == null || body.expectedVersion() == null || body.expectedVersion() < 1) {
            details.add(new ApiError.Detail("expectedVersion", "Required integer of at least 1."));
        }
        if (!details.isEmpty()) throw new RequestValidationException(details);
    }

    private static ApiError.Detail required(String field) {
        return new ApiError.Detail(field, "Required list.");
    }

    private static List<TimeInterval> intervals(List<IntervalBody> bodies) {
        return bodies.stream().map(b -> b == null ? null : b.toInterval()).toList();
    }

    private static ReaderContext reader(HttpServletRequest request) {
        var staff = (AuthenticatedStaff) request.getAttribute(AuthenticatedStaff.ATTRIBUTE);
        return new ReaderContext(staff.userId(), staff.roles());
    }
}
