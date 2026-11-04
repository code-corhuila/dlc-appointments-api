package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.Transition;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Event type and v1 payload of each lifecycle transition, exactly as the event schemas in dlc-docs declare. */
final class LifecycleEvents {
    private LifecycleEvents() { }

    record Event(String type, Map<String, Object> payload) { }

    static Event of(LifecycleCommand command, Transition transition) {
        Appointment a = transition.appointment();
        UUID actor = a.updatedBy();
        String at = a.updatedAt().toString();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("appointmentId", a.id());
        payload.put("patientId", a.patientId());
        String type = switch (command) {
            case LifecycleCommand.Confirm c -> {
                payload.put("confirmationChannel", c.channel().name());
                payload.put("confirmedAt", at);
                payload.put("recordedBy", actor);
                yield "AppointmentConfirmed";
            }
            case LifecycleCommand.Cancel c -> {
                payload.put("dentistId", a.dentistId());
                payload.put("cancelledAt", at);
                payload.put("cancelledBy", actor);
                payload.put("reason", transition.change().reason());
                payload.put("override", transition.change().override());
                yield "AppointmentCancelled";
            }
            case LifecycleCommand.Reschedule r -> {
                payload.remove("patientId");
                payload.put("previousStartAt", transition.change().previousStartAt().toInstant().toString());
                payload.put("newStartAt", a.startAt().toInstant().toString());
                payload.put("newEndAt", a.endAt().toInstant().toString());
                payload.put("newReminderDueAt", a.reminderDueAt().toInstant().toString());
                payload.put("appointmentVersion", a.version());
                payload.put("rescheduledBy", actor);
                if (transition.change().override()) payload.put("overrideReason", transition.change().reason());
                yield "AppointmentRescheduled";
            }
            case LifecycleCommand.StartAttention s -> {
                payload.put("dentistId", a.dentistId());
                payload.put("startedAt", at);
                yield "AppointmentAttentionStarted";
            }
            case LifecycleCommand.NoShow n -> {
                payload.put("dentistId", a.dentistId());
                payload.put("recordedAt", at);
                payload.put("recordedBy", actor);
                yield "AppointmentNoShow";
            }
        };
        return new Event(type, payload);
    }
}
