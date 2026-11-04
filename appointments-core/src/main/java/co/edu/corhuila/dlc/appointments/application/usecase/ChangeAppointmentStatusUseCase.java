package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.in.ChangeAppointmentStatus;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentLifecycleStore;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentReader;
import co.edu.corhuila.dlc.appointments.application.port.out.CareCompletions;
import co.edu.corhuila.dlc.appointments.application.port.out.SchedulingAuthorization;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentConflictException;
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

/**
 * HU-APT-002 orchestration: staff authorization, owner checks (active patient for attention starts and
 * reschedules), then one atomic decision in the store, which for a reschedule also rechecks availability.
 */
public final class ChangeAppointmentStatusUseCase implements ChangeAppointmentStatus {
    private final AppointmentLifecycleStore store;
    private final AppointmentReader reader;
    private final AppointmentEligibility eligibility;
    private final AppointmentAvailability availability;
    private final CareCompletions completions;
    private final SchedulingAuthorization authorization;
    private final Clock clock;

    public ChangeAppointmentStatusUseCase(AppointmentLifecycleStore store, AppointmentReader reader,
            AppointmentEligibility eligibility, AppointmentAvailability availability, CareCompletions completions,
            SchedulingAuthorization authorization, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.availability = Objects.requireNonNull(availability, "availability");
        this.completions = Objects.requireNonNull(completions, "completions");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Appointment change(LifecycleCommand command, SchedulingContext context) {
        Objects.requireNonNull(context, "context");
        authorization.requireSchedulingAllowed(context);
        Objects.requireNonNull(command, "command");
        if (command instanceof LifecycleCommand.StartAttention || command instanceof LifecycleCommand.Reschedule) {
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
            case LifecycleCommand.Reschedule r -> moved(current.reschedule(r.startAt(), r.endAt(), r.reason(),
                    r.override(), role, actor, now));
            case LifecycleCommand.StartAttention s -> current.startAttention(actor, now);
            case LifecycleCommand.Complete c -> declared(current, current.complete(actor, now));
            case LifecycleCommand.NoShow n -> current.markNoShow(n.reason(), actor, now);
        });
    }

    /** D8: finalization needs the Dentist's declaration for this appointment's patient and Dentist. */
    private Transition declared(Appointment current, Transition transition) {
        boolean matches = completions.find(current.id())
                .filter(d -> d.patientId().equals(current.patientId()) && d.dentistId().equals(current.dentistId()))
                .isPresent();
        if (!matches) {
            throw new AppointmentConflictException("The Dentist has not declared the clinical work complete.");
        }
        return transition;
    }

    /** Inside the store's transaction, after the state machine accepted the move (APT-002 for the new interval). */
    private Transition moved(Transition transition) {
        Appointment next = transition.appointment();
        if (!availability.isAvailable(next.dentistId(), next.startAt(), next.endAt(), next.id())) {
            throw new SchedulingException(SchedulingException.Reason.SLOT_UNAVAILABLE);
        }
        return transition;
    }
}
