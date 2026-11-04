package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.application.model.AssignmentPage;
import co.edu.corhuila.dlc.appointments.application.model.AssignmentQuery;
import co.edu.corhuila.dlc.appointments.application.model.SchedulingContext;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentConflictException;
import co.edu.corhuila.dlc.appointments.domain.model.AssignmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignment;
import co.edu.corhuila.dlc.appointments.domain.model.ClinicalAssignmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** HU-APT-004 assignment store against the real schema and the runtime role's grants. */
class AssignmentStoreIntegrationTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static JdbcClient jdbc;
    private static JdbcClinicalAssignmentStore store;

    @BeforeAll
    static void connect() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: integration tests skipped");
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(System.getenv("TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
        jdbc = JdbcClient.create(dataSource);
        store = new JdbcClinicalAssignmentStore(new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                jdbc, new JdbcIdempotencyStore(jdbc), new JdbcOutboxWriter(jdbc), UUID::randomUUID);
    }

    @Test
    void startCommitsTheRowTheEventAndTheKeyAndARetryReplaysIt() {
        var context = context();
        ClinicalAssignment proposed = proposed(UUID.randomUUID(), UUID.randomUUID(), "Plan");
        assertTrue(store.start(context, proposed).created());

        assertEquals(proposed.id(), store.find(proposed.id()).orElseThrow().id());
        assertEquals("ClinicalAssignmentStarted " + proposed.startedBy() + " 1", jdbc.sql("SELECT concat_ws(' ', "
                + "event_type, payload->>'startedBy', payload->>'version') FROM appointments.outbox_events "
                + "WHERE aggregate_id = ?").param(proposed.id()).query(String.class).single());
        var replay = store.recover(context, proposed(proposed.patientId(), proposed.dentistId(), "Plan"));
        assertFalse(replay.orElseThrow().created());
        assertEquals(proposed.id(), replay.get().assignment().id());
        assertEquals(SchedulingException.Reason.IDEMPOTENCY_CONFLICT, assertThrows(SchedulingException.class,
                () -> store.recover(context, proposed(proposed.patientId(), proposed.dentistId(), "Other"))).reason());
    }

    @Test
    void aSecondActiveAssignmentForThePairIsAConflictUntilTheFirstEnds() {
        UUID patient = UUID.randomUUID();
        UUID dentist = UUID.randomUUID();
        ClinicalAssignment first = proposed(patient, dentist, "Plan");
        store.start(context(), first);
        assertThrows(AppointmentConflictException.class, () -> store.start(context(), proposed(patient, dentist, "B")));

        var ending = context();
        var decisions = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            ClinicalAssignment ended = store.end(first.id(), "end-1", ending, current -> {
                decisions.incrementAndGet();
                return current.end("Finished", 1, ending.actorUserId(), NOW.plusSeconds(5));
            });
            assertEquals(AssignmentStatus.ENDED, ended.status());
            assertEquals(2, ended.version());
        }
        assertEquals(1, decisions.get());
        assertEquals("Finished", jdbc.sql("SELECT end_reason FROM appointments.clinical_assignments "
                + "WHERE assignment_id = ?").param(first.id()).query(String.class).single());
        assertEquals(1L, jdbc.sql("SELECT count(*) FROM appointments.outbox_events WHERE aggregate_id = ? "
                + "AND event_type = 'ClinicalAssignmentEnded'").param(first.id()).query(Long.class).single());
        assertTrue(store.start(context(), proposed(patient, dentist, "Again")).created());
    }

    @Test
    void endingAnUnknownAssignmentIsNotFound() {
        assertThrows(ClinicalAssignmentNotFoundException.class,
                () -> store.end(UUID.randomUUID(), "x", context(), current -> current));
    }

    @Test
    void searchFiltersBeforeCountingAndOrdersMostRecentFirst() {
        UUID patient = UUID.randomUUID();
        ClinicalAssignment older = proposedAt(patient, UUID.randomUUID(), NOW.minusSeconds(60));
        ClinicalAssignment newer = proposedAt(patient, UUID.randomUUID(), NOW);
        store.start(context(), older);
        store.start(context(), newer);

        AssignmentPage page = store.search(new AssignmentQuery(1, 1, Optional.of(patient), Optional.empty()));
        assertEquals(2, page.total());
        assertEquals(newer.id(), page.items().get(0).id());
        assertEquals(older.id(), store.search(new AssignmentQuery(2, 1, Optional.of(patient), Optional.empty()))
                .items().get(0).id());
        assertEquals(1, store.search(new AssignmentQuery(1, 20, Optional.of(patient),
                Optional.of(older.dentistId()))).total());
        assertEquals(0, store.search(new AssignmentQuery(3, 1, Optional.of(patient), Optional.empty()))
                .items().size());
    }

    private static ClinicalAssignment proposed(UUID patient, UUID dentist, String reason) {
        return ClinicalAssignment.start(UUID.randomUUID(), patient, dentist, reason, UUID.randomUUID(), NOW);
    }

    private static ClinicalAssignment proposedAt(UUID patient, UUID dentist, Instant at) {
        return ClinicalAssignment.start(UUID.randomUUID(), patient, dentist, "Plan", UUID.randomUUID(), at);
    }

    /** One fixed actor and key per context, so recover() sees what start() stored under it. */
    private static SchedulingContext context() {
        return new SchedulingContext(UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(),
                Set.of("SECRETARY_ASSISTANT"), UUID.randomUUID());
    }
}
