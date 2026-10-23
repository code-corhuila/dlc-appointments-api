package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointment;
import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointmentCommand;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentRegistration;
import co.edu.corhuila.dlc.appointments.application.port.out.SchedulingAuthorization;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingResult;
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
    private final SchedulingAuthorization authorization;

    public ScheduleAppointmentUseCase(AppointmentEligibility eligibility,
            AppointmentAvailability availability, AppointmentRegistration registration,
            SchedulingAuthorization authorization) {
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
        this.availability = Objects.requireNonNull(availability, "availability");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    @Override
    public SchedulingResult schedule(ScheduleAppointmentCommand command, SchedulingContext context) {
        Objects.requireNonNull(context, "context");
        authorization.requireSchedulingAllowed(context.actorUserId(), context.sessionId());
        Objects.requireNonNull(command, "command");
        Appointment proposed = Appointment.schedule(UUID.randomUUID(), command.patientId(),
                command.dentistId(), command.startAt(), command.endAt(), command.reason());
        var recovered = registration.recover(context, proposed);
        if (recovered.isPresent()) return recovered.get();
        return registration.register(context, proposed, () -> validateNewAppointment(proposed));
    }

    private void validateNewAppointment(Appointment proposed) {
        require(eligibility.isPatientActive(proposed.patientId()), PATIENT_INELIGIBLE);
        require(eligibility.isDentistAuthorized(proposed.dentistId()), DENTIST_INELIGIBLE);
        require(availability.isAvailable(proposed.dentistId(), proposed.startAt(), proposed.endAt()),
                SLOT_UNAVAILABLE);
    }

    private static void require(boolean satisfied, Reason reason) {
        if (!satisfied) throw new SchedulingException(reason);
    }
}
