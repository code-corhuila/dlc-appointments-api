package co.edu.corhuila.dlc.appointments.adapter.out.messaging;

/** Sends one serialized event and returns only once the broker confirmed it. */
@FunctionalInterface
public interface EventPublisher {
    void publish(String routingKey, String messageId, String json);

    /** The broker did not confirm the message; no cause or message is kept (they may hold connection details). */
    final class PublishFailedException extends RuntimeException {
        public PublishFailedException() {
            super("The broker did not confirm the event.");
        }
    }
}
