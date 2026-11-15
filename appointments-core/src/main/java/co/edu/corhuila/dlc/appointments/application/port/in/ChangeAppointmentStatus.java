package co.edu.corhuila.dlc.appointments.application.port.in;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;

/** HU-APT-002 lifecycle operations; the result is the appointment's current representation. */
public interface ChangeAppointmentStatus {
    Appointment change(LifecycleCommand command, SchedulingContext context);
}
