package co.edu.corhuila.dlc.appointments.adapter.in.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcEventInbox;
import co.edu.corhuila.dlc.appointments.application.usecase.ConsumeEvent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real RabbitMQ and PostgreSQL: the consumer declares its topology, commits through the inbox and dead-letters. */
class RabbitEventConsumerIntegrationTest {
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);
    private static final RabbitEventConsumer.Topology TOPOLOGY = new RabbitEventConsumer.Topology(
            "clinical.events.it-" + RUN, "patients.events.it-" + RUN, "appointments-it-" + RUN);
    private static CachingConnectionFactory connections;
    private static SimpleMessageListenerContainer container;
    private static JdbcClient jdbc;

    @BeforeAll
    static void start() {
        String broker = System.getenv("TEST_RABBITMQ_URL");
        String database = System.getenv("TEST_DATABASE_URL");
        assumeTrue(broker != null && !broker.isBlank() && database != null && !database.isBlank(),
                "TEST_RABBITMQ_URL or TEST_DATABASE_URL not set: consumer tests skipped");
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(database);
        dataSource.setUser(System.getenv("TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc = JdbcClient.create(dataSource);
        var inbox = new JdbcEventInbox(new TransactionTemplate(new DataSourceTransactionManager(dataSource)), jdbc);
        connections = new CachingConnectionFactory(URI.create(broker));
        container = new RabbitEventConsumer(connections, TOPOLOGY, new ConsumeEvent(inbox, Clock.systemUTC()),
                new EventEnvelopeReader(), 3, Duration.ofMillis(10)).container();
        container.start();
    }

    @AfterAll
    static void stop() {
        if (container == null) return;
        container.stop();
        var admin = new RabbitAdmin(connections);
        for (String queue : new String[] {TOPOLOGY.careQueue(), TOPOLOGY.deactivationQueue()}) {
            admin.deleteQueue(queue);
            admin.deleteQueue(RabbitEventConsumer.Topology.dlq(queue));
        }
        admin.deleteExchange(TOPOLOGY.clinicalExchange());
        admin.deleteExchange(TOPOLOGY.patientsExchange());
        admin.deleteExchange(TOPOLOGY.deadLetterExchange());
        connections.destroy();
    }

    @Test
    void aDeactivationPublishedTwiceClosesTheGateOnceAndIsAcknowledged() throws Exception {
        String body = RabbitEventConsumerTest.valid();
        String patient = body.replaceAll("(?s).*\"patientId\":\"([^\"]+)\".*", "$1");
        publish(TOPOLOGY.patientsExchange(), "PatientDeactivated", body);
        publish(TOPOLOGY.patientsExchange(), "PatientDeactivated", body);

        assertEquals("INACTIVE|1", await(() -> jdbc.sql("SELECT status || '|' || version FROM "
                + "appointments.patient_gates WHERE patient_id = ?::uuid").param(patient).query(String.class)
                .optional().orElse(null)));
        Thread.sleep(500);
        assertEquals("INACTIVE|1", jdbc.sql("SELECT status || '|' || version FROM appointments.patient_gates "
                + "WHERE patient_id = ?::uuid").param(patient).query(String.class).single());
        assertEquals(0, new RabbitAdmin(connections).getQueueInfo(TOPOLOGY.deactivationQueue()).getMessageCount());
    }

    @Test
    void invalidEventsAndRejectedDeclarationsReachTheDeadLetterQueue() throws Exception {
        publish(TOPOLOGY.patientsExchange(), "PatientDeactivated", "{not json");
        Message invalid = new RabbitTemplate(connections)
                .receive(RabbitEventConsumer.Topology.dlq(TOPOLOGY.deactivationQueue()), 5000);
        assertNotNull(invalid);
        assertEquals("{not json", new String(invalid.getBody(), StandardCharsets.UTF_8));

        UUID event = UUID.randomUUID();
        UUID record = UUID.randomUUID();
        String completion = """
                {"eventId":"%s","eventType":"ClinicalCareCompleted","aggregateId":"%s",
                 "aggregateType":"ClinicalRecord","occurredAt":"2026-11-04T15:30:00Z","version":1,
                 "producer":"clinical-service","payload":{"clinicalRecordId":"%s","consultationId":"%s",
                 "appointmentId":"%s","patientId":"%s","dentistId":"%s","procedureIds":[],"manualChargeIds":[],
                 "completedAt":"2026-11-04T15:00:00Z","clinicalVersion":1},
                 "metadata":{"correlationId":"%s","causationId":"%s","actorUserId":"%s"}}"""
                .formatted(event, record, record, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        publish(TOPOLOGY.clinicalExchange(), "ClinicalCareCompleted", completion);
        assertNotNull(new RabbitTemplate(connections).receive(RabbitEventConsumer.Topology.dlq(TOPOLOGY.careQueue()),
                5000));
        assertEquals("FAILED|UNKNOWN_APPOINTMENT", jdbc.sql("SELECT status || '|' || last_error_code FROM "
                + "appointments.inbox_events WHERE event_id = ?").param(event).query(String.class).single());
    }

    private static void publish(String exchange, String routingKey, String body) {
        var properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        new RabbitTemplate(connections).send(exchange, routingKey,
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private static <T> T await(Supplier<T> read) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            T value = read.get();
            if (value != null) return value;
            Thread.sleep(100);
        }
        return null;
    }
}
