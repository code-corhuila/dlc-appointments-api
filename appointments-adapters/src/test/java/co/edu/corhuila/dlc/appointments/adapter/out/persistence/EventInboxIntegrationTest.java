package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.application.model.InboundEvent;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.out.EventInbox;
import co.edu.corhuila.dlc.appointments.application.port.out.EventInbox.RejectedEventException;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** DB-08 inbox with the D8 declaration and the D9 PatientDeactivated gate: one effect per event id. */
class EventInboxIntegrationTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static int slot = (int) (Math.random() * 400_000);
    private static JdbcClient jdbc;
    private static JdbcAppointmentRegistration registration;
    private static JdbcEventInbox inbox;

    @BeforeAll
    static void connect() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: integration tests skipped");
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(System.getenv("TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc = JdbcClient.create(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        registration = new JdbcAppointmentRegistration(transactions, jdbc, new JdbcAppointmentRepository(jdbc,
                "America/Bogota"), new JdbcIdempotencyStore(jdbc), new JdbcOutboxWriter(jdbc), UUID::randomUUID);
        inbox = new JdbcEventInbox(transactions, jdbc);
    }

    @Test
    void aCareCompletionIsDeclaredOnceAndARedeliveryHasNoSecondEffect() {
        Appointment a = schedule(UUID.randomUUID());
        InboundEvent event = completion(UUID.randomUUID(), a, UUID.randomUUID());

        assertEquals(EventInbox.Outcome.PROCESSED, inbox.consume(event, NOW));
        assertEquals(EventInbox.Outcome.DUPLICATE, inbox.consume(event, NOW));
        assertEquals(1L, count("care_completions WHERE appointment_id = ?", a.id()));
        assertEquals("PROCESSED|" + event.correlationId() + "|clinical-service",
                jdbc.sql("SELECT status || '|' || correlation_id || '|' || producer FROM appointments.inbox_events "
                        + "WHERE event_id = ? AND processed_at IS NOT NULL AND last_error_code IS NULL")
                        .param(event.eventId()).query(String.class).single());
        assertEquals(a.patientId(), new JdbcCareCompletions(jdbc).find(a.id()).orElseThrow().patientId());
    }

    @Test
    void anEquivalentRedeclarationIsAcceptedAndAConflictingOneIsRejectedAndKeptFailed() {
        Appointment a = schedule(UUID.randomUUID());
        UUID record = UUID.randomUUID();
        inbox.consume(completion(UUID.randomUUID(), a, record), NOW);

        assertEquals(EventInbox.Outcome.PROCESSED, inbox.consume(completion(UUID.randomUUID(), a, record), NOW));
        InboundEvent conflicting = completion(UUID.randomUUID(), a, UUID.randomUUID());
        assertEquals("CONFLICTING_DECLARATION", assertThrows(RejectedEventException.class,
                () -> inbox.consume(conflicting, NOW)).code());
        assertEquals("FAILED|CONFLICTING_DECLARATION", failure(conflicting));
        assertEquals(1L, count("care_completions WHERE appointment_id = ?", a.id()));
    }

    @Test
    void aDeclarationForAnUnknownAppointmentIsRejectedWithoutEffectAndCanBeReprocessed() {
        Appointment unknown = Appointment.schedule(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                start(), start().plusMinutes(30), "Checkup", UUID.randomUUID(), NOW);
        InboundEvent event = completion(UUID.randomUUID(), unknown, UUID.randomUUID());

        assertEquals("UNKNOWN_APPOINTMENT", assertThrows(RejectedEventException.class,
                () -> inbox.consume(event, NOW)).code());
        assertEquals("UNKNOWN_APPOINTMENT", assertThrows(RejectedEventException.class,
                () -> inbox.consume(event, NOW)).code());
        assertEquals("FAILED|UNKNOWN_APPOINTMENT", failure(event));
        assertEquals(0L, count("care_completions WHERE event_id = ?", event.eventId()));
    }

    @Test
    void patientDeactivatedClosesTheGateOnceForNewSchedulingOnly() {
        UUID patient = UUID.randomUUID();
        schedule(patient);
        InboundEvent event = new InboundEvent(UUID.randomUUID(), "PatientDeactivated", 1, "patients-service",
                patient, UUID.randomUUID(), new InboundEvent.PatientDeactivation(patient, NOW));

        assertEquals(EventInbox.Outcome.PROCESSED, inbox.consume(event, NOW));
        assertEquals(EventInbox.Outcome.DUPLICATE, inbox.consume(event, NOW));
        assertEquals("INACTIVE|2", jdbc.sql("SELECT status || '|' || version FROM appointments.patient_gates "
                + "WHERE patient_id = ?").param(patient).query(String.class).single());
        assertEquals(SchedulingException.Reason.PATIENT_INELIGIBLE,
                assertThrows(SchedulingException.class, () -> schedule(patient)).reason());

        UUID unseen = UUID.randomUUID();
        inbox.consume(new InboundEvent(UUID.randomUUID(), "PatientDeactivated", 1, "patients-service", unseen,
                UUID.randomUUID(), new InboundEvent.PatientDeactivation(unseen, NOW)), NOW);
        assertEquals("INACTIVE|1", jdbc.sql("SELECT status || '|' || version FROM appointments.patient_gates "
                + "WHERE patient_id = ?").param(unseen).query(String.class).single());
    }

    private static InboundEvent completion(UUID eventId, Appointment a, UUID clinicalRecord) {
        return new InboundEvent(eventId, "ClinicalCareCompleted", 1, "clinical-service", clinicalRecord,
                UUID.randomUUID(), new InboundEvent.CareCompletionDeclared(clinicalRecord,
                        new UUID(clinicalRecord.getMostSignificantBits(), 7L), a.id(), a.patientId(), a.dentistId(),
                        NOW.minusSeconds(60), 3));
    }

    private static Appointment schedule(UUID patient) {
        OffsetDateTime start = start();
        Appointment proposed = Appointment.schedule(UUID.randomUUID(), patient, UUID.randomUUID(), start,
                start.plusMinutes(30), "Checkup", UUID.randomUUID(), NOW);
        var context = new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Set.of("SECRETARY_ASSISTANT"), UUID.randomUUID());
        return registration.register(context, proposed, () -> { }).appointment();
    }

    private static OffsetDateTime start() {
        return OffsetDateTime.parse("2043-01-01T08:00:00-05:00").plusMinutes(30L * slot++);
    }

    private static String failure(InboundEvent event) {
        return jdbc.sql("SELECT status || '|' || last_error_code FROM appointments.inbox_events "
                + "WHERE event_id = ? AND processed_at IS NULL").param(event.eventId()).query(String.class).single();
    }

    private static long count(String fromWhere, UUID id) {
        return jdbc.sql("SELECT count(*) FROM appointments." + fromWhere).param(id).query(Long.class).single();
    }
}
