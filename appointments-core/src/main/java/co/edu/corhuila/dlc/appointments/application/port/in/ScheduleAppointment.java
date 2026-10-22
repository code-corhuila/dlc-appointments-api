package co.edu.corhuila.dlc.appointments.application.port.in;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;

public interface ScheduleAppointment {
    Appointment schedule(ScheduleAppointmentCommand command);
}
