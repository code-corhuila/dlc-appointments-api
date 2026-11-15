package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.port.in.ReplaceAvailabilityCommand;
import co.edu.corhuila.dlc.appointments.application.port.in.ReplaceDentistAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.DentistAvailabilityStore;
import co.edu.corhuila.dlc.appointments.application.port.out.IdGenerator;
import co.edu.corhuila.dlc.appointments.domain.model.DentistAvailability;
import co.edu.corhuila.dlc.appointments.domain.model.DentistNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.Clock;
import java.util.Objects;

/** Secretary/Assistant or Administrator replaces the availability of an active Dentist (Auth-verified). */
public final class ReplaceDentistAvailabilityUseCase implements ReplaceDentistAvailability {
    private final DentistAvailabilityStore store;
    private final AppointmentEligibility eligibility;
    private final IdGenerator ids;
    private final Clock clock;

    public ReplaceDentistAvailabilityUseCase(DentistAvailabilityStore store, AppointmentEligibility eligibility,
            IdGenerator ids, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DentistAvailability replace(ReplaceAvailabilityCommand command, ReaderContext actor) {
        if (!actor.roles().contains("ADMINISTRATOR") && !actor.roles().contains("SECRETARY_ASSISTANT")) {
            throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
        }
        if (!eligibility.isDentistAuthorized(command.dentistId())) throw new DentistNotFoundException();
        return store.replace(command.dentistId(), (current, activeAppointments) -> DentistAvailability.replace(
                current, command.dentistId(), command.intervals(), command.blockedIntervals(),
                command.expectedVersion(), activeAppointments, actor.actorUserId(), clock.instant(), ids.newId()));
    }
}
