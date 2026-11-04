package co.edu.corhuila.dlc.appointments.application.port.in;

import co.edu.corhuila.dlc.appointments.application.model.AppointmentPage;
import co.edu.corhuila.dlc.appointments.application.model.AppointmentQuery;
import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;

public interface ListAppointments {
    AppointmentPage list(AppointmentQuery query, ReaderContext reader);
}
