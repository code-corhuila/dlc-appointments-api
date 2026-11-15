package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.application.model.AssignedPatientsPage;
import co.edu.corhuila.dlc.appointments.application.model.CareRelationship;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignment;
import co.edu.corhuila.dlc.appointments.domain.model.StaffRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** #16 internal reads: current access sources per pair and per Dentist, from authoritative rows only. */
class CareRelationshipsIntegrationTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static int slot = (int) (Math.random() * 400_000);
    private static JdbcAppointmentRegistration registration;
    private static JdbcAppointmentLifecycleStore lifecycle;
    private static JdbcClinicalAssignmentStore assignments;
    private static JdbcCareRelationships relationships;

    @BeforeAll
    static void connect() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: integration tests skipped");
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(System.getenv("TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
        var jdbc = JdbcClient.create(dataSource);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var repository = new JdbcAppointmentRepository(jdbc, "America/Bogota");
        var keys = new JdbcIdempotencyStore(jdbc);
        var outbox = new JdbcOutboxWriter(jdbc);
        registration = new JdbcAppointmentRegistration(transactions, jdbc, repository, keys, outbox, UUID::randomUUID);
        lifecycle = new JdbcAppointmentLifecycleStore(transactions, jdbc, repository, keys, outbox, UUID::randomUUID);
        assignments = new JdbcClinicalAssignmentStore(transactions, jdbc, keys, outbox, UUID::randomUUID);
        relationships = new JdbcCareRelationships(new DataSourceTransactionManager(dataSource), jdbc);
    }

    @Test
    void aPairWithBothSourcesIsAssignedOnceWithBothTypesAndLosesOnlyTheEndedOne() {
        UUID patient = UUID.randomUUID();
        UUID dentist = UUID.randomUUID();
        CareRelationship none = relationships.between(patient, dentist);
        assertFalse(none.assigned());
        assertEquals(1, none.version());
        assertEquals(List.of(), none.sourceTypes());

        Appointment appointment = schedule(patient, dentist);
        ClinicalAssignment ongoing = start(patient, dentist);
        CareRelationship both = relationships.between(patient, dentist);
        assertTrue(both.assigned());
        assertEquals(List.of("APPOINTMENT", "ONGOING_ASSIGNMENT"), both.sourceTypes());

        var actor = context();
        assignments.end(ongoing.id(), "end", actor, a -> a.end("Done", 1, actor.actorUserId(), NOW.plusSeconds(1)));
        CareRelationship appointmentOnly = relationships.between(patient, dentist);
        assertEquals(List.of("APPOINTMENT"), appointmentOnly.sourceTypes());
        assertTrue(appointmentOnly.version() > both.version());

        lifecycle.apply(new LifecycleCommand.Cancel(appointment.id(), 1, "Travel", false), actor, a -> Optional.of(
                a.cancel("Travel", false, StaffRole.SECRETARY_ASSISTANT, actor.actorUserId(), NOW)));
        CareRelationship ended = relationships.between(patient, dentist);
        assertFalse(ended.assigned());
        assertTrue(ended.version() > appointmentOnly.version());
    }

    @Test
    void aDentistsPatientsArePagedByPatientIdInsideOneVersionedSnapshot() {
        UUID dentist = UUID.randomUUID();
        UUID first = UUID.fromString("00000000-0000-4000-8000-" + String.format("%012d", slot));
        UUID second = UUID.fromString("ffffffff-0000-4000-8000-" + String.format("%012d", slot));
        schedule(second, dentist);
        start(first, dentist);
        start(second, dentist);
        Appointment cancelled = schedule(UUID.randomUUID(), dentist);
        var actor = context();
        lifecycle.apply(new LifecycleCommand.Cancel(cancelled.id(), 1, "Travel", false), actor, a -> Optional.of(
                a.cancel("Travel", false, StaffRole.SECRETARY_ASSISTANT, actor.actorUserId(), NOW)));

        AssignedPatientsPage page1 = relationships.assignedTo(dentist, 1, 1);
        AssignedPatientsPage page2 = relationships.assignedTo(dentist, 2, 1);
        assertEquals(2, page1.total());
        assertEquals(first, page1.items().get(0).patientId());
        assertEquals(List.of("ONGOING_ASSIGNMENT"), page1.items().get(0).sourceTypes());
        assertEquals(second, page2.items().get(0).patientId());
        assertEquals(List.of("APPOINTMENT", "ONGOING_ASSIGNMENT"), page2.items().get(0).sourceTypes());
        assertEquals(page1.assignmentsVersion(), page2.assignmentsVersion());
        assertEquals(0, relationships.assignedTo(dentist, 3, 1).items().size());

        start(UUID.randomUUID(), dentist);
        assertNotEquals(page1.assignmentsVersion(), relationships.assignedTo(dentist, 1, 1).assignmentsVersion());
        assertEquals(1, relationships.assignedTo(UUID.randomUUID(), 1, 20).assignmentsVersion());
    }

    private static Appointment schedule(UUID patient, UUID dentist) {
        OffsetDateTime start = OffsetDateTime.parse("2038-01-01T08:00:00-05:00").plusMinutes(30L * slot++);
        Appointment proposed = Appointment.schedule(UUID.randomUUID(), patient, dentist, start, start.plusMinutes(30),
                "Checkup", UUID.randomUUID(), NOW);
        return registration.register(context(), proposed, () -> { }).appointment();
    }

    private static ClinicalAssignment start(UUID patient, UUID dentist) {
        return assignments.start(context(), ClinicalAssignment.start(UUID.randomUUID(), patient, dentist, "Plan",
                UUID.randomUUID(), NOW)).assignment();
    }

    private static SchedulingContext context() {
        return new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Set.of("SECRETARY_ASSISTANT"), UUID.randomUUID());
    }
}
