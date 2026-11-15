package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.port.out.OutboxRelay;
import java.time.Clock;
import java.util.Objects;

/** One bounded relay pass at server time; publication never changes business state. */
public final class RelayOutbox {
    private final OutboxRelay relay;
    private final Clock clock;
    private final int batchSize;

    public RelayOutbox(OutboxRelay relay, Clock clock, int batchSize) {
        this.relay = Objects.requireNonNull(relay, "relay");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Batch size must be 1 to 1000.");
        this.batchSize = batchSize;
    }

    public int run() {
        return relay.relay(clock.instant(), batchSize);
    }
}
