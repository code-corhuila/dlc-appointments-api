package co.edu.corhuila.dlc.appointments.application.port.in;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingResult;

public interface ScheduleAppointment {
    SchedulingResult schedule(ScheduleAppointmentCommand command, SchedulingContext context);
}
