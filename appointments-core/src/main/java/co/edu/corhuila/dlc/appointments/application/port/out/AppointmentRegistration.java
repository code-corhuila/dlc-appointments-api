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

    /** Resolves the key first: a compatible confirmed operation returns its original result;
     * an incompatible intention throws SchedulingException(IDEMPOTENCY_CONFLICT), without validation.
     * Only a new operation invokes validateNewOperation, synchronously and at most once per
     * coordination attempt, before recording success. Validation failure prevents successful
     * registration; all exceptions propagate, including dependency failures.
     * Validation must not perform independent writes or publish messages.
     * Future durable coordination must cover key uniqueness, intention comparison, availability
     * changes, overlap exclusion and patient deactivation, with atomic appointment/result/outbox.
     * Early absence is not a reservation. Calling the validation alone provides no atomicity,
     * serialization or safety across processes; remote checks under locks need a later protocol.
     */
    SchedulingResult register(SchedulingContext context, Appointment proposed, Runnable validateNewOperation);
}
