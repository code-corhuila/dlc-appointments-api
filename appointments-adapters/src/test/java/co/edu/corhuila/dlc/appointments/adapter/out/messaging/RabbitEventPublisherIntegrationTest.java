package co.edu.corhuila.dlc.appointments.adapter.out.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Real RabbitMQ (TEST_RABBITMQ_URL): persistent JSON messages, routed by the PascalCase event name, confirmed. */
class RabbitEventPublisherIntegrationTest {
    private static CachingConnectionFactory connections;

    @BeforeAll
    static void connect() {
        String url = System.getenv("TEST_RABBITMQ_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_RABBITMQ_URL not set: broker tests skipped");
        connections = RabbitEventPublisher.connectionFactory(URI.create(url));
    }

    @Test
    void anEventIsConfirmedAndRoutedByItsTypeToBoundQueues() {
        String exchange = "appointments.events.it-" + UUID.randomUUID();
        var publisher = new RabbitEventPublisher(connections, exchange, Duration.ofSeconds(5));
        String id = UUID.randomUUID().toString();
        publisher.publish("AppointmentReminderRequested", id, "{\"eventId\":\"" + id + "\"}");

        var admin = new RabbitAdmin(connections);
        Queue queue = new Queue("appointments-it-" + UUID.randomUUID(), false, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(exchange)).with("AppointmentReminderRequested"));
        publisher.publish("AppointmentReminderRequested", id, "{\"eventId\":\"" + id + "\"}");
        publisher.publish("AppointmentCancelled", UUID.randomUUID().toString(), "{}");

        Message message = new RabbitTemplate(connections).receive(queue.getName(), 5000);
        assertNotNull(message);
        assertEquals("{\"eventId\":\"" + id + "\"}", new String(message.getBody(), StandardCharsets.UTF_8));
        assertEquals(id, message.getMessageProperties().getMessageId());
        assertEquals("AppointmentReminderRequested", message.getMessageProperties().getType());
        assertEquals("application/json", message.getMessageProperties().getContentType());
        assertEquals(MessageDeliveryMode.PERSISTENT, message.getMessageProperties().getReceivedDeliveryMode());
        assertEquals(null, new RabbitTemplate(connections).receive(queue.getName(), 500));
        admin.deleteQueue(queue.getName());
        admin.deleteExchange(exchange);
    }

    @Test
    void anUnreachableBrokerIsAPublishFailure() {
        var unreachable = RabbitEventPublisher.connectionFactory(URI.create("amqp://nobody:nothing@localhost:1"));
        var publisher = new RabbitEventPublisher(unreachable, "appointments.events", Duration.ofSeconds(1));
        assertThrows(EventPublisher.PublishFailedException.class, () -> publisher.publish("X", "id", "{}"));
        unreachable.destroy();
    }
}
