package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.model.AppointmentPage;
import co.edu.corhuila.dlc.appointments.application.model.AppointmentQuery;
import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.port.in.ListAppointments;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentSearch;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * HU-APT-003 calendar. Authorization narrows the query before records and totals are computed: schedulers see
 * every appointment; a Dentist sees only their own pending (PROGRAMADA, CONFIRMADA, EN_ATENCION) and performed
 * (FINALIZADA) appointments, never CANCELADA or NO_ASISTIO (decision D6).
 */
public final class ListAppointmentsUseCase implements ListAppointments {
    private static final Set<AppointmentStatus> DENTIST_VISIBLE = EnumSet.of(AppointmentStatus.PROGRAMADA,
            AppointmentStatus.CONFIRMADA, AppointmentStatus.EN_ATENCION, AppointmentStatus.FINALIZADA);
    private final AppointmentSearch appointments;

    public ListAppointmentsUseCase(AppointmentSearch appointments) {
        this.appointments = Objects.requireNonNull(appointments, "appointments");
    }

    @Override
    public AppointmentPage list(AppointmentQuery query, ReaderContext reader) {
        if (reader.roles().contains("ADMINISTRATOR") || reader.roles().contains("SECRETARY_ASSISTANT")) {
            return appointments.search(criteria(query, query.dentistId(), EnumSet.allOf(AppointmentStatus.class)));
        }
        if (reader.roles().contains("DENTIST")) {
            if (query.dentistId() != null && !query.dentistId().equals(reader.actorUserId())) {
                return new AppointmentPage(List.of(), query.page(), query.limit(), 0);
            }
            return appointments.search(criteria(query, reader.actorUserId(), DENTIST_VISIBLE));
        }
        throw new SchedulingException(SchedulingException.Reason.AUTHORIZATION_DENIED);
    }

    private static AppointmentSearch.Criteria criteria(AppointmentQuery query, java.util.UUID dentistId,
            Set<AppointmentStatus> statuses) {
        return new AppointmentSearch.Criteria(query.patientId(), dentistId, query.from(), query.to(), statuses,
                query.page(), query.limit());
    }
}
