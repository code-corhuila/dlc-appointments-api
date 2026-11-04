package co.edu.corhuila.dlc.appointments.application.port.out;

import java.time.Instant;

/**
 * APT-006: moves at most {@code limit} due PENDING reminders of live appointments that have not started yet to
 * REQUESTED, each with its delivery id and AppointmentReminderRequested event in the same transaction.
 * Returns how many were requested; safe to run concurrently and repeatedly.
 */
public interface ReminderRequests {
    int requestDue(Instant now, int limit);
}
