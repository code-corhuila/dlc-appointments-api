package co.edu.corhuila.dlc.appointments.application.port.out;

import java.time.Instant;

/**
 * Publishes at most {@code limit} committed outbox events that are due, at least once and in recording order;
 * consumers deduplicate by eventId. Returns how many were published. Safe to run concurrently.
 */
public interface OutboxRelay {
    int relay(Instant now, int limit);
}
