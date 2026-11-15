package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.port.in.ReadAppointment;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentReader;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentNotFoundException;
import java.util.Objects;
import java.util.UUID;

/**
 * HU-APT-003: Administrator and Secretary/Assistant read any appointment; a Dentist reads only appointments
 * assigned to them. An out-of-scope appointment answers exactly like a missing one, to avoid enumeration.
 */
public final class ReadAppointmentUseCase implements ReadAppointment {
    private final AppointmentReader appointments;

    public ReadAppointmentUseCase(AppointmentReader appointments) {
        this.appointments = Objects.requireNonNull(appointments, "appointments");
    }

    @Override
    public Appointment read(UUID appointmentId, ReaderContext reader) {
        Appointment appointment = appointments.findById(appointmentId).orElseThrow(AppointmentNotFoundException::new);
        if (reader.roles().contains("ADMINISTRATOR") || reader.roles().contains("SECRETARY_ASSISTANT")) {
            return appointment;
        }
        if (reader.roles().contains("DENTIST") && appointment.dentistId().equals(reader.actorUserId())) {
            return appointment;
        }
        throw new AppointmentNotFoundException();
    }
}
