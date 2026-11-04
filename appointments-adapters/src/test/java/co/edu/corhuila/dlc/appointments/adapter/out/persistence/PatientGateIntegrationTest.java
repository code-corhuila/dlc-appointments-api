package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.port.in.LifecycleCommand;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignment;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import co.edu.corhuila.dlc.appointments.domain.model.StaffRole;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** D9 / ADR-017: scheduling, reschedules, attention starts and assignment starts commit only through an OPEN gate. */
class PatientGateIntegrationTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static int slot = (int) (Math.random() * 400_000);
    private static JdbcClient jdbc;
    private static JdbcAppointmentRegistration registration;
    private static JdbcAppointmentLifecycleStore lifecycle;
    private static JdbcClinicalAssignmentStore assignments;

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
        var repository = new JdbcAppointmentRepository(jdbc, "America/Bogota");
        var keys = new JdbcIdempotencyStore(jdbc);
        var outbox = new JdbcOutboxWriter(jdbc);
        registration = new JdbcAppointmentRegistration(transactions, jdbc, repository, keys, outbox, UUID::randomUUID);
        lifecycle = new JdbcAppointmentLifecycleStore(transactions, jdbc, repository, keys, outbox, UUID::randomUUID);
        assignments = new JdbcClinicalAssignmentStore(transactions, jdbc, keys, outbox, UUID::randomUUID);
    }

    @Test
    void schedulingOpensTheGateOnceAndABlockedGateRejectsItWithoutWriting() {
        UUID patient = UUID.randomUUID();
        schedule(patient);
        assertEquals("OPEN", gate(patient));

        UUID blocked = UUID.randomUUID();
        setGate(blocked, "BLOCKED");
        Appointment proposed = proposal(blocked);
        assertEquals(SchedulingException.Reason.PATIENT_INELIGIBLE, assertThrows(SchedulingException.class,
                () -> registration.register(context(), proposed, () -> { })).reason());
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM appointments.appointments WHERE patient_id = ?")
                .param(blocked).query(Long.class).single());
    }

    @Test
    void anInactivePatientGetsNoNewAssignment() {
        UUID patient = UUID.randomUUID();
        setGate(patient, "INACTIVE");
        assertEquals(SchedulingException.Reason.PATIENT_INELIGIBLE, assertThrows(SchedulingException.class,
                () -> assignments.start(context(), ClinicalAssignment.start(UUID.randomUUID(), patient,
                        UUID.randomUUID(), "Plan", UUID.randomUUID(), NOW))).reason());
    }

    @Test
    void aBlockedGateStopsReschedulesAndAttentionStartsButNotCancellations() {
        UUID patient = UUID.randomUUID();
        Appointment a = schedule(patient);
        setGate(patient, "BLOCKED");
        var actor = context();
        OffsetDateTime next = a.startAt().plusDays(1);
        assertEquals(SchedulingException.Reason.PATIENT_INELIGIBLE, assertThrows(SchedulingException.class,
                () -> lifecycle.apply(new LifecycleCommand.Reschedule(a.id(), 1, next, next.plusMinutes(30), "Move",
                        false), actor, c -> Optional.of(c.reschedule(next, next.plusMinutes(30), "Move", false,
                        StaffRole.SECRETARY_ASSISTANT, actor.actorUserId(), NOW)))).reason());
        assertEquals(SchedulingException.Reason.PATIENT_INELIGIBLE, assertThrows(SchedulingException.class,
                () -> lifecycle.apply(new LifecycleCommand.StartAttention(a.id(), 1), actor,
                        c -> Optional.of(c.startAttention(actor.actorUserId(), NOW)))).reason());
        assertEquals(AppointmentStatus.CANCELADA, lifecycle.apply(new LifecycleCommand.Cancel(a.id(), 1, "Leaving",
                false), actor, c -> Optional.of(c.cancel("Leaving", false, StaffRole.SECRETARY_ASSISTANT,
                actor.actorUserId(), NOW))).status());
    }

    private static Appointment schedule(UUID patient) {
        return registration.register(context(), proposal(patient), () -> { }).appointment();
    }

    private static Appointment proposal(UUID patient) {
        OffsetDateTime start = OffsetDateTime.parse("2041-01-01T08:00:00-05:00").plusMinutes(30L * slot++);
        return Appointment.schedule(UUID.randomUUID(), patient, UUID.randomUUID(), start, start.plusMinutes(30),
                "Checkup", UUID.randomUUID(), NOW);
    }

    private static void setGate(UUID patient, String status) {
        jdbc.sql("INSERT INTO appointments.patient_gates (patient_id, status, version, updated_at) VALUES (?, ?, 2, now()) "
                + "ON CONFLICT (patient_id) DO UPDATE SET status = EXCLUDED.status, version = 2")
                .params(patient, status).update();
    }

    private static String gate(UUID patient) {
        return jdbc.sql("SELECT status FROM appointments.patient_gates WHERE patient_id = ?").param(patient)
                .query(String.class).single();
    }

    private static SchedulingContext context() {
        return new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Set.of("SECRETARY_ASSISTANT"), UUID.randomUUID());
    }
}
