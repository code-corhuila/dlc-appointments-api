package co.edu.corhuila.dlc.appointments.application.model;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import java.util.List;

/** One bounded page (Norma 5.3.6); total counts only records the reader may see. */
public record AppointmentPage(List<Appointment> items, int page, int limit, long total) {
    public AppointmentPage {
        items = List.copyOf(items);
    }

    public long totalPages() {
        return (total + limit - 1) / limit;
    }
}
