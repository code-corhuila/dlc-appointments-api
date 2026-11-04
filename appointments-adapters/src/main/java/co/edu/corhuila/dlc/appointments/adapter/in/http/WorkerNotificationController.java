package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.AuthenticatedService;
import co.edu.corhuila.dlc.appointments.application.port.out.ReminderOutcomes;
import co.edu.corhuila.dlc.appointments.application.usecase.ReminderNotifications;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** ADR-005 private contract for dlc-worker: minimum reminder details and idempotent delivery outcomes. */
@RestController
public class WorkerNotificationController {
    private static final String BASE = "/internal/v1/appointment-notifications/{appointmentId}";
    private static final String DETAILS_READ = "appointments:notification-details:read";
    private static final String OUTCOME_WRITE = "appointments:notification-outcome:write";
    private final ReminderNotifications notifications;

    public WorkerNotificationController(ReminderNotifications notifications) {
        this.notifications = Objects.requireNonNull(notifications, "notifications");
    }

    public record Details(UUID appointmentId, UUID patientId, long appointmentVersion, String startAt, String endAt,
            String status) { }

    public record OutcomeRequest(UUID deliveryId, Long appointmentVersion, String notificationType, String status,
            Integer attempts, OffsetDateTime occurredAt, String providerMessageId, String failureCode) { }

    @GetMapping(BASE + "/details")
    public Details details(@PathVariable("appointmentId") UUID appointmentId, HttpServletRequest request) {
        requireScope(request, DETAILS_READ);
        var d = notifications.details(appointmentId);
        return new Details(d.appointmentId(), d.patientId(), d.appointmentVersion(), d.startAt().toInstant().toString(),
                d.endAt().toInstant().toString(), d.status());
    }

    /** 200 without a body (the contract declares none) whether recorded now or recognized as a repeat. */
    @PostMapping(path = BASE + "/outcomes", consumes = "application/json")
    public ResponseEntity<Void> outcome(@PathVariable("appointmentId") UUID appointmentId,
            @RequestBody(required = false) OutcomeRequest body, HttpServletRequest request) {
        requireScope(request, OUTCOME_WRITE);
        var o = body == null ? new OutcomeRequest(null, null, null, null, null, null, null, null) : body;
        var details = new ArrayList<ApiError.Detail>();
        if (o.deliveryId() == null) details.add(new ApiError.Detail("deliveryId", "Required UUID."));
        if (o.appointmentVersion() == null || o.appointmentVersion() < 1) {
            details.add(new ApiError.Detail("appointmentVersion", "Required, at least 1."));
        }
        if (!"REMINDER".equals(o.notificationType())) {
            details.add(new ApiError.Detail("notificationType", "Must be REMINDER."));
        }
        if (!Set.of("SENT", "DELIVERY_FAILED").contains(String.valueOf(o.status()))) {
            details.add(new ApiError.Detail("status", "Must be SENT or DELIVERY_FAILED."));
        }
        if (o.attempts() == null || o.attempts() < 1 || o.attempts() > 3) {
            details.add(new ApiError.Detail("attempts", "Required, 1 to 3."));
        }
        if (o.occurredAt() == null) details.add(new ApiError.Detail("occurredAt", "Required RFC 3339 timestamp."));
        if ("SENT".equals(o.status()) && (o.providerMessageId() == null || o.providerMessageId().isBlank()
                || o.providerMessageId().length() > 200)) {
            details.add(new ApiError.Detail("providerMessageId", "Required for SENT, 1 to 200 characters."));
        }
        if ("DELIVERY_FAILED".equals(o.status())
                && (o.failureCode() == null || !o.failureCode().matches("[A-Z][A-Z0-9_]{0,63}"))) {
            details.add(new ApiError.Detail("failureCode", "Required for DELIVERY_FAILED, SCREAMING_SNAKE_CASE."));
        }
        if (!details.isEmpty()) throw new RequestValidationException(details);
        notifications.record(new ReminderOutcomes.Outcome(appointmentId, o.deliveryId(),
                o.appointmentVersion(), o.status(), o.attempts(), o.occurredAt().toInstant(),
                "SENT".equals(o.status()) ? o.providerMessageId() : null,
                "DELIVERY_FAILED".equals(o.status()) ? o.failureCode() : null));
        return ResponseEntity.ok().build();
    }

    private static void requireScope(HttpServletRequest request, String scope) {
        var service = (AuthenticatedService) request.getAttribute(AuthenticatedService.ATTRIBUTE);
        if (service == null || !service.hasScope(scope)) {
            throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
        }
    }
}
