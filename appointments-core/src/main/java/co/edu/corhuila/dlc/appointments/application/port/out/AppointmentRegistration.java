package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;

/** Single registration boundary; no separate persistent effects in the use case.
 * A future adapter must enforce overlap protection and the patient gate at commit,
 * atomically with business state/outbox and idempotency. Earlier reads are insufficient.
 * Failures propagate; this interface alone supplies no transaction implementation.
 * Returns the authoritative registered or recovered appointment, not necessarily the proposal.
 * Real idempotency and atomicity remain unimplemented. Internal operation-context transport
 * is pending design: the UUID retry key comes from a header and the actor from verified identity,
 * scoped to operation/resource. Neither belongs in CreateAppointment; retries require authorization.
 */
public interface AppointmentRegistration {
    Appointment register(Appointment appointment);
}
