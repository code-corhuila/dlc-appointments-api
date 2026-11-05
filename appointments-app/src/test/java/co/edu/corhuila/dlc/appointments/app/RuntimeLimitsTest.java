package co.edu.corhuila.dlc.appointments.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Norma 5.3.10: every server limit is declared with its value in the composition root. */
@SpringBootTest
class RuntimeLimitsTest {
    @Autowired
    private ServerProperties server;
    @Autowired
    private LifecycleProperties lifecycle;
    @Autowired
    private AppointmentsProperties appointments;

    @DynamicPropertySource
    static void identity(DynamicPropertyRegistry registry) {
        TestIdentity.register(registry);
    }

    @Test
    void headerReadTimeoutIsFiveSeconds() {
        assertThat(server.getTomcat().getConnectionTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void idleKeepAliveTimeoutIsSixtySeconds() {
        assertThat(server.getTomcat().getKeepAliveTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void shutdownIsGracefulWithinTwentySeconds() {
        assertThat(server.getShutdown()).isEqualTo(Shutdown.GRACEFUL);
        assertThat(lifecycle.getTimeoutPerShutdownPhase()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void outboundAuthCallsHaveAnExplicitDeadline() {
        assertThat(appointments.auth().timeout()).isEqualTo(Duration.ofSeconds(2));
    }
}
