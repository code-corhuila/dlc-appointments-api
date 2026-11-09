package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentTypes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/v1/appointment-types (HU-APT-006): read-only catalog for scheduling and the calendar legend. */
@RestController
public class AppointmentTypeController {
    private final AppointmentTypes types;

    public AppointmentTypeController(AppointmentTypes types) {
        this.types = Objects.requireNonNull(types, "types");
    }

    @GetMapping("/api/v1/appointment-types")
    public Map<String, List<Map<String, String>>> list() {
        return Map.of("data", types.listActive().stream().map(type -> {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("code", type.code());
            item.put("label", type.label());
            item.put("color", type.color());
            return item;
        }).toList());
    }
}
