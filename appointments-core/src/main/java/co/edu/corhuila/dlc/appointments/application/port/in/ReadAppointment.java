package co.edu.corhuila.dlc.appointments.application.port.in;

import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import java.util.UUID;

public interface ReadAppointment {
    /** Throws AppointmentNotFoundException when absent or outside the reader's scope. */
    Appointment read(UUID appointmentId, ReaderContext reader);
}
