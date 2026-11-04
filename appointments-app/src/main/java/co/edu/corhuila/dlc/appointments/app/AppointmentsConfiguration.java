package co.edu.corhuila.dlc.appointments.app;

import co.edu.corhuila.dlc.appointments.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.dlc.appointments.adapter.in.http.ReadinessProbe;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcAppointmentRepository;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcIdempotencyStore;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcOutboxWriter;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.RsaPublicKeys;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.StaffAuthenticationFilter;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.StaffAuthenticationVerifier;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.StaffJwtSignatureVerifier;
import co.edu.corhuila.dlc.appointments.adapter.out.auth.AuthSessionValidationClient;
import co.edu.corhuila.dlc.appointments.adapter.out.authorization.RoleSchedulingAuthorization;
import co.edu.corhuila.dlc.appointments.adapter.out.eligibility.HttpAppointmentEligibility;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcAppointmentAvailability;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcAppointmentRegistration;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcDentistAvailabilityStore;
import co.edu.corhuila.dlc.appointments.application.port.in.ListAppointments;
import co.edu.corhuila.dlc.appointments.application.port.in.ReadDentistAvailability;
import co.edu.corhuila.dlc.appointments.application.port.in.ReplaceDentistAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.usecase.ReadDentistAvailabilityUseCase;
import co.edu.corhuila.dlc.appointments.application.usecase.ReplaceDentistAvailabilityUseCase;
import co.edu.corhuila.dlc.appointments.application.port.in.ReadAppointment;
import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointment;
import co.edu.corhuila.dlc.appointments.application.usecase.ListAppointmentsUseCase;
import co.edu.corhuila.dlc.appointments.application.usecase.ReadAppointmentUseCase;
import co.edu.corhuila.dlc.appointments.application.usecase.ScheduleAppointmentUseCase;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Composition root: the only place that knows every concrete adapter type. */
@Configuration
@EnableConfigurationProperties(AppointmentsProperties.class)
public class AppointmentsConfiguration {

    /** First filter, so every later log line and error envelope carries the correlation ID. */
    @Bean
    FilterRegistrationBean<CorrelationFilter> correlationFilter() {
        var registration = new FilterRegistrationBean<>(new CorrelationFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    FilterRegistrationBean<StaffAuthenticationFilter> staffAuthenticationFilter(AppointmentsProperties properties) {
        var security = properties.security();
        var jwt = new StaffJwtSignatureVerifier(
                RsaPublicKeys.fromConfiguration(security.jwtPublicKey(), security.jwtPublicKeyFile()),
                security.jwtIssuer(), security.jwtAudience());
        var registration = new FilterRegistrationBean<>(new StaffAuthenticationFilter(
                new StaffAuthenticationVerifier(jwt, authSessions(properties.auth()))));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    JdbcAppointmentRepository appointmentRepository(JdbcClient jdbc, AppointmentsProperties properties) {
        return new JdbcAppointmentRepository(jdbc, properties.clinicTimezone());
    }

    @Bean
    JdbcIdempotencyStore idempotencyStore(JdbcClient jdbc) {
        return new JdbcIdempotencyStore(jdbc);
    }

    @Bean
    JdbcOutboxWriter outboxWriter(JdbcClient jdbc) {
        return new JdbcOutboxWriter(jdbc);
    }

    /** Readiness checks only this service's own database, within the 5 s connection wait. */
    @Bean
    ReadinessProbe readinessProbe(JdbcClient jdbc) {
        return () -> Integer.valueOf(1).equals(jdbc.sql("SELECT 1").query(Integer.class).single());
    }

    /** APT-001 owner checks: Patients for active patients, Auth for active Dentists. */
    @Bean
    AppointmentEligibility appointmentEligibility(AppointmentsProperties properties) {
        var auth = properties.auth();
        var patients = properties.patients();
        return new HttpAppointmentEligibility(ownerClient(auth.timeout()),
                required(patients.internalUrl(), "PATIENTS_INTERNAL_URL"), patients::currentServiceToken,
                auth.internalUrl(), auth::currentServiceToken);
    }

    @Bean
    JdbcDentistAvailabilityStore availabilityStore(TransactionTemplate transactions, JdbcClient jdbc) {
        return new JdbcDentistAvailabilityStore(transactions, jdbc, UUID::randomUUID);
    }

    /** HU-APT-001 availability, contract 1.4.0: schedulers replace it; a Dentist reads only their own. */
    @Bean
    ReplaceDentistAvailability replaceDentistAvailability(JdbcDentistAvailabilityStore store,
            AppointmentEligibility eligibility) {
        return new ReplaceDentistAvailabilityUseCase(store, eligibility, UUID::randomUUID, Clock.systemUTC());
    }

    @Bean
    ReadDentistAvailability readDentistAvailability(JdbcDentistAvailabilityStore store,
            AppointmentEligibility eligibility) {
        return new ReadDentistAvailabilityUseCase(store, eligibility);
    }

    /** HU-APT-001: Secretary/Assistant or Administrator schedules after owner eligibility checks. */
    @Bean
    ScheduleAppointment scheduleAppointment(AppointmentEligibility eligibility, JdbcClient jdbc,
            TransactionTemplate transactions, JdbcAppointmentRepository appointments, JdbcIdempotencyStore keys,
            JdbcOutboxWriter outbox) {
        return new ScheduleAppointmentUseCase(eligibility, new JdbcAppointmentAvailability(jdbc),
                new JdbcAppointmentRegistration(transactions, jdbc, appointments, keys, outbox, UUID::randomUUID),
                new RoleSchedulingAuthorization(), UUID::randomUUID, Clock.systemUTC());
    }

    /** HU-APT-003: scoped read of the authoritative appointment. */
    @Bean
    ReadAppointment readAppointment(JdbcAppointmentRepository appointments) {
        return new ReadAppointmentUseCase(appointments);
    }

    /** HU-APT-003: calendar scoped by role before records and totals. */
    @Bean
    ListAppointments listAppointments(JdbcAppointmentRepository appointments) {
        return new ListAppointmentsUseCase(appointments);
    }

    private static AuthSessionValidationClient authSessions(AppointmentsProperties.Auth auth) {
        return new AuthSessionValidationClient(ownerClient(auth.timeout()), auth.internalUrl(),
                auth::currentServiceToken);
    }

    /** No redirects and explicit deadlines: an owner answers in time or the request fails closed with 503. */
    private static RestClient ownerClient(Duration timeout) {
        var transport = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(timeout).build();
        var requests = new JdkClientHttpRequestFactory(transport);
        requests.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(requests).build();
    }

    private static URI required(URI origin, String variable) {
        if (origin == null || origin.getHost() == null) {
            throw new IllegalStateException(variable + " must be an absolute URL.");
        }
        return origin;
    }
}
