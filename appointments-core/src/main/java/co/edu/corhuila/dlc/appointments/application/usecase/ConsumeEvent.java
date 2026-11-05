package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.model.InboundEvent;
import co.edu.corhuila.dlc.appointments.application.port.out.EventInbox;
import java.time.Clock;
import java.util.Objects;

/** Consumes one validated event through the inbox at server time; the caller acknowledges only after it returns. */
public final class ConsumeEvent {
    private final EventInbox inbox;
    private final Clock clock;

    public ConsumeEvent(EventInbox inbox, Clock clock) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public EventInbox.Outcome handle(InboundEvent event) {
        return inbox.consume(Objects.requireNonNull(event, "event"), clock.instant());
    }
}
