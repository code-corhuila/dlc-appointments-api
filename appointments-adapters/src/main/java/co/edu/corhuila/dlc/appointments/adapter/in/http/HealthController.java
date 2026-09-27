package co.edu.corhuila.dlc.appointments.adapter.in.http;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(
                Map.of(
                        "status", "UP",
                        "service", "dlc-appointments-api"
                )
        );
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> readiness() {
        return ResponseEntity.ok(
                Map.of(
                        "status", "READY",
                        "service", "dlc-appointments-api"
                )
        );
    }
}