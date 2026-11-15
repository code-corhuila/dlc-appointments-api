package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.port.out.ReminderRequests;
import java.time.Clock;
import java.util.Objects;

/** One bounded pass of the informational reminder schedule (HU-APT-002) at server time. */
public final class RequestDueReminders {
    private final ReminderRequests reminders;
    private final Clock clock;
    private final int batchSize;

    public RequestDueReminders(ReminderRequests reminders, Clock clock, int batchSize) {
        this.reminders = Objects.requireNonNull(reminders, "reminders");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Batch size must be 1 to 1000.");
        this.batchSize = batchSize;
    }

    public int run() {
        return reminders.requestDue(clock.instant(), batchSize);
    }
}
