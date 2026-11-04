package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.in.ChangeAppointmentStatus;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentLifecycleStore;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentReader;
import co.edu.corhuila.dlc.appointments.application.port.out.SchedulingAuthorization;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import co.edu.corhuila.dlc.appointments.domain.model.StaffRole;
import co.edu.corhuila.dlc.appointments.domain.model.StaleVersionException;
import co.edu.corhuila.dlc.appointments.domain.model.Transition;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** HU-APT-002 orchestration: staff authorization, owner checks, then one atomic decision in the store. */
public final class ChangeAppointmentStatusUseCase implements ChangeAppointmentStatus {
    private final AppointmentLifecycleStore store;
    private final AppointmentReader reader;
    private final AppointmentEligibility eligibility;
    private final SchedulingAuthorization authorization;
    private final Clock clock;

    public ChangeAppointmentStatusUseCase(AppointmentLifecycleStore store, AppointmentReader reader,
            AppointmentEligibility eligibility, SchedulingAuthorization authorization, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Appointment change(LifecycleCommand command, SchedulingContext context) {
        Objects.requireNonNull(context, "context");
        authorization.requireSchedulingAllowed(context);
        Objects.requireNonNull(command, "command");
        if (command instanceof LifecycleCommand.StartAttention) {
            Appointment current = reader.findById(command.appointmentId())
                    .orElseThrow(AppointmentNotFoundException::new);
            if (!eligibility.isPatientActive(current.patientId())) {
                throw new SchedulingException(SchedulingException.Reason.PATIENT_INELIGIBLE);
            }
        }
        StaffRole role = context.roles().contains("ADMINISTRATOR")
                ? StaffRole.ADMINISTRATOR : StaffRole.SECRETARY_ASSISTANT;
        return store.apply(command, context, current -> decide(command, current, role, context.actorUserId()));
    }

    /** D4: a repeated cancellation is answered as is; otherwise the version must match before the state machine. */
    private Optional<Transition> decide(LifecycleCommand command, Appointment current, StaffRole role, UUID actor) {
        if (command instanceof LifecycleCommand.Cancel && current.status() == AppointmentStatus.CANCELADA) {
            return Optional.empty();
        }
        if (current.version() != command.expectedVersion()) {
            throw new StaleVersionException();
        }
        Instant now = clock.instant();
        return Optional.of(switch (command) {
            case LifecycleCommand.Confirm c -> current.confirm(c.channel(), actor, now);
            case LifecycleCommand.Cancel c -> current.cancel(c.reason(), c.override(), role, actor, now);
            case LifecycleCommand.StartAttention s -> current.startAttention(actor, now);
            case LifecycleCommand.NoShow n -> current.markNoShow(n.reason(), actor, now);
        });
    }
}
