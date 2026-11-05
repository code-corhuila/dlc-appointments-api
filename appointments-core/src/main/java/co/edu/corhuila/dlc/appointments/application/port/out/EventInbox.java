package co.edu.corhuila.dlc.appointments.application.port.out;

import co.edu.corhuila.dlc.appointments.application.model.InboundEvent;
import java.time.Instant;
import java.util.Objects;

/**
 * DB-08 consumer inbox: the event's effect and its inbox row commit together, so a redelivered event id has no
 * second effect. A permanent rejection is committed as FAILED with a safe code and then thrown; any other failure
 * rolls back and leaves no row, so the delivery can be retried.
 */
public interface EventInbox {
    Outcome consume(InboundEvent event, Instant receivedAt);

    enum Outcome { PROCESSED, DUPLICATE }

    /** Permanent rejection recorded as FAILED; the message belongs in the dead-letter queue. */
    final class RejectedEventException extends RuntimeException {
        private final String code;

        public RejectedEventException(String code) {
            super(Objects.requireNonNull(code, "code"), null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
