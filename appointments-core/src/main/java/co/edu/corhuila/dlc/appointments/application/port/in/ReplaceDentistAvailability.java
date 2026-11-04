package co.edu.corhuila.dlc.appointments.application.port.in;

import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.domain.model.DentistAvailability;

public interface ReplaceDentistAvailability {
    DentistAvailability replace(ReplaceAvailabilityCommand command, ReaderContext actor);
}
