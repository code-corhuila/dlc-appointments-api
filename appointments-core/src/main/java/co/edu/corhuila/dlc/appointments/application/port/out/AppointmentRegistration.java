package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingResult;
import java.util.Optional;

/** Single registration boundary; no separate persistent effects in the use case.
 * A future adapter must enforce overlap protection and the patient gate at commit,
 * atomically with business state/outbox and idempotency. Earlier reads are insufficient.
 * Failures propagate; this interface alone supplies no transaction implementation.
 * Scope is actor + scheduling operation + appointments collection + key, excluding session/proposed ID.
 * Durable idempotency and atomicity remain unimplemented; payload compatibility policy is pending.
 */
public interface AppointmentRegistration {
    /** Original compatible result or confirmed absence. Conflict throws SchedulingException
     * (IDEMPOTENCY_CONFLICT); failed/unknown lookup throws DEPENDENCY_UNAVAILABLE, never empty.
     * Compare the five creation fields, excluding the generated proposal ID. No reservation is made.
     */
    Optional<SchedulingResult> recover(SchedulingContext context, Appointment proposed);

    /** Returns the authoritative original result. Future transaction must resolve the key again
     * before rejecting availability; an earlier empty lookup cannot establish concurrency safety.
     */
    SchedulingResult register(SchedulingContext context, Appointment proposed);
}
