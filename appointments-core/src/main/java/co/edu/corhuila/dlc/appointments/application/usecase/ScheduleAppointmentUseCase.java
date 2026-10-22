package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointment;
import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointmentCommand;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentRegistration;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException.Reason;
import java.util.UUID;
import java.util.Objects;
import static co.edu.corhuila.dlc.appointments.domain.model.SchedulingException.Reason.*;

/** Initial orchestration only; atomic registration guarantees require a future adapter. */
public final class ScheduleAppointmentUseCase implements ScheduleAppointment {
    private final AppointmentEligibility eligibility;
    private final AppointmentAvailability availability;
    private final AppointmentRegistration registration;

    public ScheduleAppointmentUseCase(AppointmentEligibility eligibility,
            AppointmentAvailability availability, AppointmentRegistration registration) {
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.availability = Objects.requireNonNull(availability, "availability");
        this.registration = Objects.requireNonNull(registration, "registration");
    }

    @Override
    public Appointment schedule(ScheduleAppointmentCommand command) {
        Appointment proposed = Appointment.schedule(UUID.randomUUID(), command.patientId(),
                command.dentistId(), command.startAt(), command.endAt(), command.reason());
        require(eligibility.isPatientActive(proposed.patientId()), PATIENT_INELIGIBLE);
        require(eligibility.isDentistAuthorized(proposed.dentistId()), DENTIST_INELIGIBLE);
        require(availability.isAvailable(proposed.dentistId(), proposed.startAt(), proposed.endAt()),
                SLOT_UNAVAILABLE);
        return registration.register(proposed);
    }

    private static void require(boolean satisfied, Reason reason) {
        if (!satisfied) throw new SchedulingException(reason);
    }
}
