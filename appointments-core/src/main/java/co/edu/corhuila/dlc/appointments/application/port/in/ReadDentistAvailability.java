package co.edu.corhuila.dlc.appointments.application.port.in;

import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.domain.model.DentistAvailability;
import java.util.UUID;

public interface ReadDentistAvailability {
    /** Throws DentistNotFoundException when absent, not an active Dentist or outside the reader's scope. */
    DentistAvailability read(UUID dentistId, ReaderContext reader);
}
