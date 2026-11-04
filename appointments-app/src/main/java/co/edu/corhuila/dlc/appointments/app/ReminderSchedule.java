package co.edu.corhuila.dlc.appointments.app;

import co.edu.corhuila.dlc.appointments.application.usecase.RequestDueReminders;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic, bounded reminder requests; a failed pass is logged without details and retried on the next one. */
@Component
@ConditionalOnProperty(name = "appointments.reminders.enabled", havingValue = "true", matchIfMissing = true)
class ReminderSchedule {
    private static final Logger LOG = LoggerFactory.getLogger(ReminderSchedule.class);
    private final RequestDueReminders reminders;

    ReminderSchedule(RequestDueReminders reminders) {
        this.reminders = Objects.requireNonNull(reminders, "reminders");
    }

    @Scheduled(fixedDelayString = "${appointments.reminders.interval:PT30S}", initialDelayString = "PT10S")
    void requestDue() {
        try {
            int requested = reminders.run();
            if (requested > 0) LOG.info("Requested {} appointment reminders.", requested);
        } catch (RuntimeException failure) {
            LOG.warn("Reminder pass failed: {}", failure.getClass().getSimpleName());
        }
    }
}
