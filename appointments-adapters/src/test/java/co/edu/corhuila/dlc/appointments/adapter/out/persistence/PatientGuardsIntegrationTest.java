package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.application.usecase.PatientGuards;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentConflictException;
import co.edu.corhuila.dlc.appointments.domain.model.GuardKind;
import co.edu.corhuila.dlc.appointments.domain.model.GuardState;
import co.edu.corhuila.dlc.appointments.domain.model.PatientGuardNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** ADR-017 guards (D9): idempotent by operationId with token rotation, outcome-only resolution, never by time. */
class PatientGuardsIntegrationTest {
    private static final String PATIENTS = "patients-service";
    private static final String CLINICAL = "clinical-service";
    private static int slot = (int) (Math.random() * 400_000);
    private static JdbcClient jdbc;
    private static JdbcAppointmentRegistration registration;
    private static PatientGuards guards;

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
        guards = new PatientGuards(new JdbcPatientGuardStore(transactions, jdbc), UUID::randomUUID, Clock.systemUTC());
    }

    @Test
    void repeatingAnAcquisitionReturnsTheSameGuardWithARotatedTokenAndOnlyTheLatestResolves() {
        UUID patient = UUID.randomUUID();
        UUID operation = UUID.randomUUID();
        var first = guards.acquire(patient, operation, PATIENTS, GuardKind.DEACTIVATION);
        var again = guards.acquire(patient, operation, PATIENTS, GuardKind.DEACTIVATION);

        assertEquals(first.guard().id(), again.guard().id());
        assertNotEquals(first.token(), again.token());
        assertEquals("BLOCKED", gate(patient));
        assertEquals(first.guard().id(), guards.byOperation(operation, PATIENTS).id());
        assertThrows(PatientGuardNotFoundException.class, () -> guards.byOperation(operation, CLINICAL));
        assertThrows(PatientGuardNotFoundException.class, () -> guards.byId(first.guard().id(), CLINICAL));

        assertThrows(AppointmentConflictException.class,
                () -> guards.resolve(first.guard().id(), PATIENTS, first.token(), GuardState.FINALIZED));
        assertEquals(GuardState.FINALIZED,
                guards.resolve(first.guard().id(), PATIENTS, again.token(), GuardState.FINALIZED).state());
        assertEquals(GuardState.FINALIZED,
                guards.resolve(first.guard().id(), PATIENTS, again.token(), GuardState.FINALIZED).state());
        assertThrows(AppointmentConflictException.class,
                () -> guards.resolve(first.guard().id(), PATIENTS, again.token(), GuardState.ABORTED));
        assertEquals("INACTIVE", gate(patient));
        assertTrue(jdbc.sql("SELECT token_hash FROM appointments.patient_guards WHERE guard_id = ?")
                .param(first.guard().id()).query(String.class).single().matches("[0-9a-f]{64}"));
    }

    @Test
    void deactivationWaitsForLiveAppointmentsAndActivityGuardsAndAnAbortReopensTheGate() {
        UUID patient = UUID.randomUUID();
        schedule(patient);
        assertThrows(AppointmentConflictException.class,
                () -> guards.acquire(patient, UUID.randomUUID(), PATIENTS, GuardKind.DEACTIVATION));

        UUID other = UUID.randomUUID();
        var activity = guards.acquire(other, UUID.randomUUID(), CLINICAL, GuardKind.ACTIVITY);
        assertThrows(AppointmentConflictException.class,
                () -> guards.acquire(other, UUID.randomUUID(), PATIENTS, GuardKind.DEACTIVATION));
        guards.resolve(activity.guard().id(), CLINICAL, activity.token(), GuardState.FINALIZED);
        var deactivation = guards.acquire(other, UUID.randomUUID(), PATIENTS, GuardKind.DEACTIVATION);
        assertThrows(AppointmentConflictException.class,
                () -> guards.acquire(other, UUID.randomUUID(), CLINICAL, GuardKind.ACTIVITY));
        guards.resolve(deactivation.guard().id(), PATIENTS, deactivation.token(), GuardState.ABORTED);
        assertEquals("OPEN", gate(other));
        assertEquals(GuardState.OPEN, guards.acquire(other, UUID.randomUUID(), CLINICAL, GuardKind.ACTIVITY)
                .guard().state());
    }

    @Test
    void schedulingAndDeactivationNeverBothCommit() throws Exception {
        for (int round = 0; round < 10; round++) {
            UUID patient = UUID.randomUUID();
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Boolean>> results = new ArrayList<>();
                results.add(pool.submit(race(start, () -> schedule(patient))));
                results.add(pool.submit(race(start, () -> guards.acquire(patient, UUID.randomUUID(), PATIENTS,
                        GuardKind.DEACTIVATION))));
                start.countDown();
                boolean scheduled = results.get(0).get();
                boolean blocked = results.get(1).get();
                assertTrue(scheduled ^ blocked, "exactly one of scheduling and deactivation commits");
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static Callable<Boolean> race(CountDownLatch start, Callable<?> action) {
        return () -> {
            start.await();
            try {
                action.call();
                return true;
            } catch (AppointmentConflictException | SchedulingException rejected) {
                return false;
            } catch (ExecutionException unexpected) {
                throw unexpected;
            }
        };
    }

    private static Appointment schedule(UUID patient) {
        OffsetDateTime start = OffsetDateTime.parse("2042-01-01T08:00:00-05:00").plusMinutes(30L * slot++);
        Appointment proposed = Appointment.schedule(UUID.randomUUID(), patient, UUID.randomUUID(), start,
                start.plusMinutes(30), "Checkup", UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        var context = new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Set.of("SECRETARY_ASSISTANT"), UUID.randomUUID());
        return registration.register(context, proposed, () -> { }).appointment();
    }

    private static String gate(UUID patient) {
        return jdbc.sql("SELECT status FROM appointments.patient_gates WHERE patient_id = ?").param(patient)
                .query(String.class).single();
    }
}
