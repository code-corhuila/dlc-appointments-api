package co.edu.corhuila.dlc.appointments.app;

import co.edu.corhuila.dlc.appointments.application.usecase.RelayOutbox;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic outbox relay; a failed pass is logged without details and retried on the next one. */
@Component
@ConditionalOnProperty(name = "appointments.messaging.relay-enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelaySchedule {
    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelaySchedule.class);
    private final RelayOutbox relay;

    OutboxRelaySchedule(RelayOutbox relay) {
        this.relay = Objects.requireNonNull(relay, "relay");
    }

    @Scheduled(fixedDelayString = "${appointments.messaging.relay-interval:PT2S}", initialDelayString = "PT5S")
    void relay() {
        try {
            int published = relay.run();
            if (published > 0) LOG.info("Published {} outbox events.", published);
        } catch (RuntimeException failure) {
            LOG.warn("Outbox relay pass failed: {}", failure.getClass().getSimpleName());
        }
    }
}
