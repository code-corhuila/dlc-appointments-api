package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.port.out.ReminderOutcomes;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentNotFoundException;
import java.util.Objects;
import java.util.UUID;

/** dlc-worker reads and reports; the calling service's scope is checked by the inbound adapter. */
public final class ReminderNotifications {
    private final ReminderOutcomes outcomes;

    public ReminderNotifications(ReminderOutcomes outcomes) {
        this.outcomes = Objects.requireNonNull(outcomes, "outcomes");
    }

    public ReminderOutcomes.Details details(UUID appointmentId) {
        return outcomes.details(appointmentId).orElseThrow(AppointmentNotFoundException::new);
    }

    /** Recording never changes the appointment's lifecycle state (business state is unchanged). */
    public boolean record(ReminderOutcomes.Outcome outcome) {
        return outcomes.record(Objects.requireNonNull(outcome, "outcome"));
    }
}
