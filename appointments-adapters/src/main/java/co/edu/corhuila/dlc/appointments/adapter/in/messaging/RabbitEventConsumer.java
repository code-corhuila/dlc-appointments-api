package co.edu.corhuila.dlc.appointments.adapter.in.messaging;

import co.edu.corhuila.dlc.appointments.application.model.InboundEvent;
import co.edu.corhuila.dlc.appointments.application.port.out.EventInbox.RejectedEventException;
import co.edu.corhuila.dlc.appointments.application.usecase.ConsumeEvent;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;

/**
 * Appointments' consumer queues (communication-patterns.md): one durable purpose-specific queue per consumed event,
 * bound to the producer's topic exchange by the PascalCase event name, each with its own dead-letter queue. The
 * message is acknowledged only after the inbox transaction commits. Transient failures are retried with bounded
 * exponential backoff; invalid, rejected or exhausted messages are dead-lettered and never requeued.
 */
public final class RabbitEventConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(RabbitEventConsumer.class);
    private final CachingConnectionFactory connections;
    private final Topology topology;
    private final ConsumeEvent consume;
    private final EventEnvelopeReader reader;
    private final int attempts;
    private final Duration backoff;

    public RabbitEventConsumer(CachingConnectionFactory connections, Topology topology, ConsumeEvent consume,
                               EventEnvelopeReader reader, int attempts, Duration backoff) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.topology = Objects.requireNonNull(topology, "topology");
        this.consume = Objects.requireNonNull(consume, "consume");
        this.reader = Objects.requireNonNull(reader, "reader");
        if (attempts < 1 || attempts > 10) throw new IllegalArgumentException("Attempts must be 1 to 10.");
        this.attempts = attempts;
        this.backoff = Objects.requireNonNull(backoff, "backoff");
    }

    /** Queue and exchange names; the producers' exchanges are declared with the same durable topic type. */
    public record Topology(String clinicalExchange, String patientsExchange, String prefix) {
        public Topology {
            Objects.requireNonNull(clinicalExchange, "clinicalExchange");
            Objects.requireNonNull(patientsExchange, "patientsExchange");
            Objects.requireNonNull(prefix, "prefix");
        }

        public String careQueue() {
            return prefix + ".clinical-care-completed";
        }

        public String deactivationQueue() {
            return prefix + ".patient-deactivated";
        }

        public String deadLetterExchange() {
            return prefix + ".consumers.dlx";
        }

        public static String dlq(String queue) {
            return queue + ".dlq";
        }
    }

    /**
     * The topology is declared on every new broker connection, so a broker that starts later or loses its state is
     * repaired on reconnect. Missing queues are not fatal; the container keeps retrying.
     */
    public SimpleMessageListenerContainer container() {
        connections.addConnectionListener((ConnectionListener) connection -> {
            try (Channel channel = connection.createChannel(false)) {
                declare(channel);
            } catch (IOException | TimeoutException failed) {
                throw new AmqpIOException(new IOException("Consumer topology declaration failed."));
            }
        });
        var container = new SimpleMessageListenerContainer(connections);
        container.setQueueNames(topology.careQueue(), topology.deactivationQueue());
        container.setAcknowledgeMode(AcknowledgeMode.AUTO);
        container.setDefaultRequeueRejected(false);
        container.setMissingQueuesFatal(false);
        container.setPrefetchCount(10);
        container.setRecoveryInterval(5000);
        container.setMessageListener(this::onMessage);
        return container;
    }

    void onMessage(Message message) {
        InboundEvent event;
        try {
            event = reader.read(message.getBody());
        } catch (EventEnvelopeReader.InvalidEventException invalid) {
            LOG.warn("Event dead-lettered: {}", invalid.code());
            throw new AmqpRejectAndDontRequeueException(invalid.code());
        }
        for (int attempt = 1; ; attempt++) {
            try {
                consume.handle(event);
                return;
            } catch (RejectedEventException rejected) {
                LOG.warn("Event {} dead-lettered: {}", event.eventId(), rejected.code());
                throw new AmqpRejectAndDontRequeueException(rejected.code());
            } catch (RuntimeException transientFailure) {
                if (attempt >= attempts) {
                    LOG.warn("Event {} dead-lettered after {} attempts: {}", event.eventId(), attempts,
                            transientFailure.getClass().getSimpleName());
                    throw new AmqpRejectAndDontRequeueException("RETRIES_EXHAUSTED");
                }
                pause(backoff.multipliedBy(1L << (attempt - 1)));
            }
        }
    }

    private void declare(Channel channel) throws IOException {
        channel.exchangeDeclare(topology.clinicalExchange(), "topic", true);
        channel.exchangeDeclare(topology.patientsExchange(), "topic", true);
        channel.exchangeDeclare(topology.deadLetterExchange(), "direct", true);
        queue(channel, topology.careQueue(), topology.clinicalExchange(), "ClinicalCareCompleted");
        queue(channel, topology.deactivationQueue(), topology.patientsExchange(), "PatientDeactivated");
    }

    private void queue(Channel channel, String queue, String exchange, String eventType) throws IOException {
        channel.queueDeclare(Topology.dlq(queue), true, false, false, null);
        channel.queueBind(Topology.dlq(queue), topology.deadLetterExchange(), queue);
        channel.queueDeclare(queue, true, false, false, Map.of("x-dead-letter-exchange",
                topology.deadLetterExchange(), "x-dead-letter-routing-key", queue));
        channel.queueBind(queue, exchange, eventType);
    }

    private static void pause(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new ImmediateRequeueAmqpException("Consumer stopping.");
        }
    }
}
