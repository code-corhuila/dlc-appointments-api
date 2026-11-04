package co.edu.corhuila.dlc.appointments.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.DentistAvailability;
import co.edu.corhuila.dlc.appointments.domain.model.StaleVersionException;
import co.edu.corhuila.dlc.appointments.domain.model.TimeInterval;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Availability storage against the real schema: whole replacement, versioning and active bookings. */
class DentistAvailabilityStoreIntegrationTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static JdbcDentistAvailabilityStore store;
    private static JdbcAppointmentRepository appointments;

    @BeforeAll
    static void connect() {
        String url = System.getenv("TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "TEST_DATABASE_URL not set: integration tests skipped");
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        dataSource.setUser(System.getenv("TEST_DATABASE_USER"));
        dataSource.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
        var jdbc = JdbcClient.create(dataSource);
        store = new JdbcDentistAvailabilityStore(new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                jdbc, UUID::randomUUID);
        appointments = new JdbcAppointmentRepository(jdbc, "America/Bogota");
    }

    @Test
    void firstReplacementCreatesAndLaterOnesRewriteEveryInterval() {
        UUID dentist = UUID.randomUUID();
        DentistAvailability v1 = replace(dentist, List.of(i("08:00", "12:00"), i("14:00", "18:00")),
                List.of(i("10:00", "10:30")), 1);
        DentistAvailability stored = store.find(dentist).orElseThrow();
        assertEquals(v1.id(), stored.id());
        assertEquals(1, stored.version());
        assertEquals(2, stored.intervals().size());
        assertEquals(1, stored.blockedIntervals().size());
        assertEquals(i("14:00", "18:00").start().toInstant(), stored.intervals().get(1).start().toInstant());

        replace(dentist, List.of(i("09:00", "11:00")), List.of(), 1);
        DentistAvailability v2 = store.find(dentist).orElseThrow();
        assertEquals(2, v2.version());
        assertEquals(List.of(i("09:00", "11:00").end().toInstant()),
                v2.intervals().stream().map(x -> x.end().toInstant()).toList());
        assertTrue(v2.blockedIntervals().isEmpty());
        assertThrows(StaleVersionException.class, () -> replace(dentist, List.of(), List.of(), 1));
    }

    @Test
    void unknownDentistHasNoStoredAvailability() {
        assertTrue(store.find(UUID.randomUUID()).isEmpty());
    }

    @Test
    void changeSeesTheDentistsActiveAppointments() {
        UUID dentist = UUID.randomUUID();
        replace(dentist, List.of(i("08:00", "12:00")), List.of(), 1);
        OffsetDateTime start = i("09:00", "09:30").start();
        appointments.insert(Appointment.schedule(UUID.randomUUID(), UUID.randomUUID(), dentist, start,
                start.plusMinutes(30), "Checkup", UUID.randomUUID(), NOW));
        var seen = new AtomicReference<List<TimeInterval>>();
        store.replace(dentist, (current, active) -> {
            seen.set(active);
            return DentistAvailability.replace(current, dentist, List.of(i("08:00", "12:00")), List.of(), 1, active,
                    UUID.randomUUID(), NOW, UUID.randomUUID());
        });
        assertEquals(1, seen.get().size());
        assertEquals(start.toInstant(), seen.get().get(0).start().toInstant());
    }

    private static DentistAvailability replace(UUID dentist, List<TimeInterval> intervals,
            List<TimeInterval> blocked, long expected) {
        return store.replace(dentist, (current, active) -> DentistAvailability.replace(current, dentist, intervals,
                blocked, expected, active, UUID.randomUUID(), NOW, UUID.randomUUID()));
    }

    private static TimeInterval i(String start, String end) {
        return new TimeInterval(OffsetDateTime.parse("2033-03-03T" + start + ":00-05:00"),
                OffsetDateTime.parse("2033-03-03T" + end + ":00-05:00"));
    }
}
