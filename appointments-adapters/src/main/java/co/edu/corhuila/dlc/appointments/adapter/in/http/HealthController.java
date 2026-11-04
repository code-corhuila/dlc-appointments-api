package co.edu.corhuila.dlc.appointments.adapter.in.http;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness answers while the process runs; readiness checks only this service's own database. */
@RestController
public class HealthController {
    private final ReadinessProbe readiness;

    public HealthController(ReadinessProbe readiness) {
        this.readiness = Objects.requireNonNull(readiness, "readiness");
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "dlc-appointments-api"));
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> readiness() {
        boolean ready;
        try {
            ready = readiness.ready();
        } catch (RuntimeException unavailable) {
            ready = false;
        }
        return ResponseEntity.status(ready ? 200 : 503)
                .body(Map.of("status", ready ? "READY" : "NOT_READY", "service", "dlc-appointments-api"));
    }
}
