package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.port.in.ReadDentistAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.DentistAvailabilityStore;
import co.edu.corhuila.dlc.appointments.domain.model.DentistAvailability;
import co.edu.corhuila.dlc.appointments.domain.model.DentistNotFoundException;
import java.util.Objects;
import java.util.UUID;

/**
 * Schedulers read any active Dentist's availability; a Dentist reads only their own. Missing availability,
 * a non-Dentist id and another dentist's availability all answer 404 alike (decision G3).
 */
public final class ReadDentistAvailabilityUseCase implements ReadDentistAvailability {
    private final DentistAvailabilityStore store;
    private final AppointmentEligibility eligibility;

    public ReadDentistAvailabilityUseCase(DentistAvailabilityStore store, AppointmentEligibility eligibility) {
        this.store = Objects.requireNonNull(store, "store");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
    }

    @Override
    public DentistAvailability read(UUID dentistId, ReaderContext reader) {
        boolean scheduler = reader.roles().contains("ADMINISTRATOR")
                || reader.roles().contains("SECRETARY_ASSISTANT");
        boolean ownCalendar = reader.roles().contains("DENTIST") && reader.actorUserId().equals(dentistId);
        if (!scheduler && !ownCalendar) throw new DentistNotFoundException();
        if (!eligibility.isDentistAuthorized(dentistId)) throw new DentistNotFoundException();
        return store.find(dentistId).orElseThrow(DentistNotFoundException::new);
    }
}
