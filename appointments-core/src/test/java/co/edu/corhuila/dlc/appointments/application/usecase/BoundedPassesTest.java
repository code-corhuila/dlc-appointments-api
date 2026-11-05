package co.edu.corhuila.dlc.appointments.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The outbox relay and the reminder pass run at server time with a batch of 1 to 1000. */
class BoundedPassesTest {
    private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void theRelayPublishesOneBoundedBatchAtServerTime() {
        List<String> calls = new ArrayList<>();
        var relay = new RelayOutbox((now, limit) -> {
            calls.add(now + "|" + limit);
            return 3;
        }, CLOCK, 50);
        assertEquals(3, relay.run());
        assertEquals(List.of(NOW + "|50"), calls);
    }

    @Test
    void theReminderPassRequestsOneBoundedBatchAtServerTime() {
        List<String> calls = new ArrayList<>();
        var pass = new RequestDueReminders((now, limit) -> {
            calls.add(now + "|" + limit);
            return 2;
        }, CLOCK, 1000);
        assertEquals(2, pass.run());
        assertEquals(List.of(NOW + "|1000"), calls);
    }

    @Test
    void batchesOutsideOneToAThousandAreRejected() {
        for (int size : new int[] {0, 1001}) {
            assertThrows(IllegalArgumentException.class, () -> new RelayOutbox((now, limit) -> 0, CLOCK, size));
            assertThrows(IllegalArgumentException.class,
                    () -> new RequestDueReminders((now, limit) -> 0, CLOCK, size));
        }
        assertThrows(NullPointerException.class, () -> new RelayOutbox(null, CLOCK, 1));
        assertThrows(NullPointerException.class, () -> new RequestDueReminders((now, limit) -> 0, null, 1));
    }
}
