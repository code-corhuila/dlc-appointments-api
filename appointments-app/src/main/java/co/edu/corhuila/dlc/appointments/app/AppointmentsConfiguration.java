package co.edu.corhuila.dlc.appointments.app;

import co.edu.corhuila.dlc.appointments.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.dlc.appointments.adapter.in.http.ReadinessProbe;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcAppointmentRepository;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcAppointmentTypes;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentTypes;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcIdempotencyStore;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcOutboxWriter;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcEventInbox;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcPatientGuardStore;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcReminderOutcomes;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcReminderRequests;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.RsaPublicKeys;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.ServiceAuthenticationFilter;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.ServiceJwtVerifier;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.StaffAuthenticationFilter;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.StaffAuthenticationVerifier;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.StaffJwtSignatureVerifier;
import co.edu.corhuila.dlc.appointments.adapter.out.auth.AuthSessionValidationClient;
import co.edu.corhuila.dlc.appointments.adapter.out.auth.IamServiceTokenSupplier;
import co.edu.corhuila.dlc.appointments.adapter.out.authorization.RoleSchedulingAuthorization;
import co.edu.corhuila.dlc.appointments.adapter.out.eligibility.HttpAppointmentEligibility;
import co.edu.corhuila.dlc.appointments.adapter.in.messaging.EventEnvelopeReader;
import co.edu.corhuila.dlc.appointments.adapter.in.messaging.RabbitEventConsumer;
import co.edu.corhuila.dlc.appointments.adapter.out.messaging.JdbcOutboxRelay;
import co.edu.corhuila.dlc.appointments.adapter.out.messaging.RabbitEventPublisher;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcAppointmentAvailability;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcAppointmentLifecycleStore;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcCareCompletions;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcCareRelationships;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcClinicalAssignmentStore;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcAppointmentRegistration;
import co.edu.corhuila.dlc.appointments.adapter.out.persistence.JdbcDentistAvailabilityStore;
import co.edu.corhuila.dlc.appointments.application.port.in.ChangeAppointmentStatus;
import co.edu.corhuila.dlc.appointments.application.port.in.ListAppointments;
import co.edu.corhuila.dlc.appointments.application.port.in.ReadDentistAvailability;
import co.edu.corhuila.dlc.appointments.application.port.in.ReplaceDentistAvailability;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.usecase.ReadDentistAvailabilityUseCase;
import co.edu.corhuila.dlc.appointments.application.usecase.RelayOutbox;
import co.edu.corhuila.dlc.appointments.application.usecase.ReminderNotifications;
import co.edu.corhuila.dlc.appointments.application.usecase.RequestDueReminders;
import co.edu.corhuila.dlc.appointments.application.usecase.ReplaceDentistAvailabilityUseCase;
import co.edu.corhuila.dlc.appointments.application.port.in.ReadAppointment;
import co.edu.corhuila.dlc.appointments.application.port.in.ScheduleAppointment;
import co.edu.corhuila.dlc.appointments.application.usecase.ChangeAppointmentStatusUseCase;
import co.edu.corhuila.dlc.appointments.application.usecase.ClinicalAssignmentsUseCase;
import co.edu.corhuila.dlc.appointments.application.usecase.InternalRelationshipReads;
import co.edu.corhuila.dlc.appointments.application.usecase.ConsumeEvent;
import co.edu.corhuila.dlc.appointments.application.usecase.ListAppointmentsUseCase;
import co.edu.corhuila.dlc.appointments.application.usecase.PatientGuards;
import co.edu.corhuila.dlc.appointments.application.usecase.ReadAppointmentUseCase;
import co.edu.corhuila.dlc.appointments.application.usecase.ScheduleAppointmentUseCase;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.util.function.Supplier;
import java.time.Duration;
import java.util.UUID;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
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
                new StaffAuthenticationVerifier(jwt, authSessions(properties))));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    /** /internal/v1 only: service JWTs with this service's audience; scopes are checked per operation. */
    @Bean
    FilterRegistrationBean<ServiceAuthenticationFilter> serviceAuthenticationFilter(AppointmentsProperties properties) {
        var internal = properties.internal();
        var registration = new FilterRegistrationBean<>(new ServiceAuthenticationFilter(new ServiceJwtVerifier(
                RsaPublicKeys.fromConfiguration(internal.jwtPublicKey(), internal.jwtPublicKeyFile()),
                internal.jwtIssuer(), internal.jwtAudience(), Clock.systemUTC())));
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
                required(patients.internalUrl(), "PATIENTS_INTERNAL_URL"),
                serviceToken(properties, "patients-api", "patients:validate", patients::currentServiceToken),
                auth.internalUrl(), serviceToken(properties, "auth-service", "identity:validate",
                        auth::currentServiceToken));
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
            JdbcOutboxWriter outbox, AppointmentTypes appointmentTypes) {
        return new ScheduleAppointmentUseCase(eligibility, new JdbcAppointmentAvailability(jdbc),
                new JdbcAppointmentRegistration(transactions, jdbc, appointments, keys, outbox, UUID::randomUUID),
                new RoleSchedulingAuthorization(), UUID::randomUUID, Clock.systemUTC(), appointmentTypes);
    }

    /** HU-APT-006: migration-owned appointment-type catalog. */
    @Bean
    AppointmentTypes appointmentTypes(JdbcClient jdbc) {
        return new JdbcAppointmentTypes(jdbc);
    }

    /** HU-APT-002: staff-only lifecycle changes, each one atomic in PostgreSQL. */
    @Bean
    ChangeAppointmentStatus changeAppointmentStatus(TransactionTemplate transactions, JdbcClient jdbc,
            JdbcAppointmentRepository appointments, JdbcIdempotencyStore keys, JdbcOutboxWriter outbox,
            AppointmentEligibility eligibility) {
        return new ChangeAppointmentStatusUseCase(new JdbcAppointmentLifecycleStore(transactions, jdbc, appointments,
                keys, outbox, UUID::randomUUID), appointments, eligibility, new JdbcAppointmentAvailability(jdbc),
                new JdbcCareCompletions(jdbc), new RoleSchedulingAuthorization(), Clock.systemUTC());
    }

    /** HU-APT-004: ongoing assignments after Patients/Auth eligibility, atomic in PostgreSQL. */
    @Bean
    ClinicalAssignmentsUseCase clinicalAssignments(TransactionTemplate transactions, JdbcClient jdbc,
            JdbcIdempotencyStore keys, JdbcOutboxWriter outbox, AppointmentEligibility eligibility) {
        return new ClinicalAssignmentsUseCase(new JdbcClinicalAssignmentStore(transactions, jdbc, keys, outbox,
                UUID::randomUUID), eligibility, UUID::randomUUID, Clock.systemUTC());
    }

    /** #16: authoritative relationship and encounter reads for Clinical over /internal/v1. */
    @Bean
    InternalRelationshipReads internalRelationshipReads(DataSource dataSource, JdbcClient jdbc,
            JdbcAppointmentRepository appointments, AppointmentEligibility eligibility) {
        return new InternalRelationshipReads(new JdbcCareRelationships(
                new DataSourceTransactionManager(dataSource), jdbc), appointments, eligibility);
    }

    /** APT-006: bounded passes over due reminders; several instances never request the same one. */
    @Bean
    RequestDueReminders requestDueReminders(TransactionTemplate transactions, JdbcClient jdbc, JdbcOutboxWriter outbox,
            @org.springframework.beans.factory.annotation.Value("${appointments.reminders.batch-size:100}") int batch) {
        return new RequestDueReminders(new JdbcReminderRequests(transactions, jdbc, outbox, UUID::randomUUID),
                Clock.systemUTC(), batch);
    }

    /** ADR-005: dlc-worker reads minimum reminder details and reports delivery outcomes. */
    @Bean
    ReminderNotifications reminderNotifications(TransactionTemplate transactions, JdbcClient jdbc,
            JdbcOutboxWriter outbox) {
        return new ReminderNotifications(new JdbcReminderOutcomes(transactions, jdbc, outbox, UUID::randomUUID));
    }

    /** Outbox to RabbitMQ (appointments.events); required configuration only when the relay is enabled. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "appointments.messaging.relay-enabled",
            havingValue = "true", matchIfMissing = true)
    RelayOutbox relayOutbox(TransactionTemplate transactions, JdbcClient jdbc, AppointmentsProperties properties) {
        var messaging = properties.messaging();
        if (messaging == null || messaging.rabbitmqUri() == null || messaging.rabbitmqUri().getHost() == null) {
            throw new IllegalStateException("APPOINTMENTS_RABBITMQ_URI must be an absolute amqp URI.");
        }
        var publisher = new RabbitEventPublisher(RabbitEventPublisher.connectionFactory(messaging.rabbitmqUri()),
                messaging.exchange(), Duration.ofSeconds(5));
        return new RelayOutbox(new JdbcOutboxRelay(transactions, jdbc, publisher), Clock.systemUTC(),
                messaging.relayBatchSize());
    }

    /** ClinicalCareCompleted (D8) and PatientDeactivated (D9) through the inbox; acknowledged after commit. */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "appointments.messaging.consumers-enabled", havingValue = "true", matchIfMissing = true)
    SimpleMessageListenerContainer eventConsumers(TransactionTemplate transactions, JdbcClient jdbc,
            AppointmentsProperties properties) {
        var messaging = properties.messaging();
        if (messaging == null || messaging.rabbitmqUri() == null || messaging.rabbitmqUri().getHost() == null) {
            throw new IllegalStateException("APPOINTMENTS_RABBITMQ_URI must be an absolute amqp URI.");
        }
        var connections = new CachingConnectionFactory(messaging.rabbitmqUri());
        connections.setConnectionTimeout(5000);
        var topology = new RabbitEventConsumer.Topology(messaging.clinicalExchange(), messaging.patientsExchange(),
                "appointments");
        return new RabbitEventConsumer(connections, topology,
                new ConsumeEvent(new JdbcEventInbox(transactions, jdbc), Clock.systemUTC()),
                new EventEnvelopeReader(), messaging.consumerAttempts(), messaging.consumerBackoff()).container();
    }

    /** #17 ADR-017 guards for Patients and Clinical, serialized on the patient gate. */
    @Bean
    PatientGuards patientGuards(TransactionTemplate transactions, JdbcClient jdbc) {
        return new PatientGuards(new JdbcPatientGuardStore(transactions, jdbc), UUID::randomUUID, Clock.systemUTC());
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

    private static AuthSessionValidationClient authSessions(AppointmentsProperties properties) {
        var auth = properties.auth();
        return new AuthSessionValidationClient(ownerClient(auth.timeout()), auth.internalUrl(),
                serviceToken(properties, "auth-service", "identity:validate", auth::currentServiceToken));
    }

    /** ADR-020: an IAM-issued token for the audience when a service client is configured, else the file. */
    private static Supplier<String> serviceToken(AppointmentsProperties properties, String audience, String scope,
            Supplier<String> file) {
        var client = properties.serviceClient();
        if (client == null || !client.issuesTokens()) return file;
        return new IamServiceTokenSupplier(required(properties.auth().internalUrl(), "AUTH_INTERNAL_URL"),
                client.id(), Path.of(client.secretFile()), audience, scope, properties.auth().timeout(),
                Clock.systemUTC());
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
