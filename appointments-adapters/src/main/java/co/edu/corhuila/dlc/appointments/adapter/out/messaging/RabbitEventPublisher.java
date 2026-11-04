package co.edu.corhuila.dlc.appointments.adapter.out.messaging;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * RabbitMQ publication to the durable topic exchange {@code appointments.events} (events.md), with the exact
 * PascalCase event name as routing key, persistent JSON messages and publisher confirms. Bindings belong to
 * consumers; this publisher only declares its own exchange.
 */
public final class RabbitEventPublisher implements EventPublisher {
    private final RabbitTemplate template;
    private final RabbitAdmin admin;
    private final String exchange;
    private final Duration confirmTimeout;
    private volatile boolean declared;

    public RabbitEventPublisher(CachingConnectionFactory connections, String exchange, Duration confirmTimeout) {
        this.template = new RabbitTemplate(Objects.requireNonNull(connections, "connections"));
        this.admin = new RabbitAdmin(connections);
        this.exchange = Objects.requireNonNull(exchange, "exchange");
        this.confirmTimeout = Objects.requireNonNull(confirmTimeout, "confirmTimeout");
    }

    /** Simple publisher confirms and a bounded connection timeout; credentials come only from the URI. */
    public static CachingConnectionFactory connectionFactory(URI uri) {
        var connections = new CachingConnectionFactory(Objects.requireNonNull(uri, "uri"));
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.SIMPLE);
        connections.setConnectionTimeout(5000);
        return connections;
    }

    @Override
    public void publish(String routingKey, String messageId, String json) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setMessageId(messageId);
        properties.setType(routingKey);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        var message = new Message(json.getBytes(StandardCharsets.UTF_8), properties);
        try {
            if (!declared) {
                admin.declareExchange(new TopicExchange(exchange, true, false));
                declared = true;
            }
            template.invoke(operations -> {
                operations.send(exchange, routingKey, message);
                operations.waitForConfirmsOrDie(confirmTimeout.toMillis());
                return null;
            });
        } catch (RuntimeException unconfirmed) {
            throw new PublishFailedException();
        }
    }
}
