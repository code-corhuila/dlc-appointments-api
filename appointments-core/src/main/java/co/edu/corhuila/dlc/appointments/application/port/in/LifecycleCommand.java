package co.edu.corhuila.dlc.appointments.application.port.in;

import co.edu.corhuila.dlc.appointments.domain.model.ConfirmationChannel;
import co.edu.corhuila.dlc.appointments.domain.model.LifecycleOperation;
import java.util.UUID;

/** One HU-APT-002 request against an existing appointment, with the version the caller last read. */
public sealed interface LifecycleCommand {
    UUID appointmentId();

    long expectedVersion();

    LifecycleOperation operation();

    record Confirm(UUID appointmentId, long expectedVersion, ConfirmationChannel channel) implements LifecycleCommand {
        public LifecycleOperation operation() { return LifecycleOperation.CONFIRM; }
    }

    record Cancel(UUID appointmentId, long expectedVersion, String reason, boolean override)
            implements LifecycleCommand {
        public LifecycleOperation operation() { return LifecycleOperation.CANCEL; }
    }

    record StartAttention(UUID appointmentId, long expectedVersion) implements LifecycleCommand {
        public LifecycleOperation operation() { return LifecycleOperation.START_ATTENTION; }
    }

    record NoShow(UUID appointmentId, long expectedVersion, String reason) implements LifecycleCommand {
        public LifecycleOperation operation() { return LifecycleOperation.NO_SHOW; }
    }
}
