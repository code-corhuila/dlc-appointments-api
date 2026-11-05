package co.edu.corhuila.dlc.appointments.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The running application consumes PatientDeactivated from patients.events through its own queue and inbox. */
@SpringBootTest
class EventConsumersWiringTest {
    private static final String PATIENTS_EXCHANGE = "patients.events.app-it-" + UUID.randomUUID();

    @Autowired
    private SimpleMessageListenerContainer consumers;

    @Autowired
    private JdbcClient jdbc;

    @BeforeAll
    static void requireInfrastructure() {
        String broker = System.getenv("TEST_RABBITMQ_URL");
        String database = System.getenv("TEST_DATABASE_URL");
        assumeTrue(broker != null && !broker.isBlank() && database != null && !database.isBlank(),
                "TEST_RABBITMQ_URL or TEST_DATABASE_URL not set: consumer wiring test skipped");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
        registry.add("spring.datasource.url", () -> System.getenv("TEST_DATABASE_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("TEST_DATABASE_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("TEST_DATABASE_PASSWORD"));
        registry.add("appointments.messaging.consumers-enabled", () -> "true");
        registry.add("appointments.messaging.rabbitmq-uri", () -> System.getenv("TEST_RABBITMQ_URL"));
        registry.add("appointments.messaging.patients-exchange", () -> PATIENTS_EXCHANGE);
    }

    @Test
    void aPublishedDeactivationClosesThePatientGate() throws Exception {
        assertTrue(consumers.isRunning());
        UUID patient = UUID.randomUUID();
        String body = """
                {"eventId":"%s","eventType":"PatientDeactivated","aggregateId":"%s","aggregateType":"Patient",
                 "occurredAt":"2026-11-04T15:30:00Z","version":1,"producer":"patients-service",
                 "payload":{"patientId":"%s","reasonCode":"OTHER","deactivatedAt":"2026-11-04T15:30:00Z",
                 "deactivatedBy":"%s"},
                 "metadata":{"correlationId":"%s","causationId":"%s","actorUserId":null}}"""
                .formatted(UUID.randomUUID(), patient, patient, UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID());
        var connections = new CachingConnectionFactory(URI.create(System.getenv("TEST_RABBITMQ_URL")));
        try {
            for (int i = 0; i < 50 && gate(patient) == null; i++) {
                new RabbitTemplate(connections).send(PATIENTS_EXCHANGE, "PatientDeactivated",
                        new Message(body.getBytes(StandardCharsets.UTF_8), new MessageProperties()));
                Thread.sleep(200);
            }
        } finally {
            connections.destroy();
        }
        assertEquals("INACTIVE", gate(patient));
    }

    private String gate(UUID patient) {
        return jdbc.sql("SELECT status FROM appointments.patient_gates WHERE patient_id = ?").param(patient)
                .query(String.class).optional().orElse(null);
    }
}
