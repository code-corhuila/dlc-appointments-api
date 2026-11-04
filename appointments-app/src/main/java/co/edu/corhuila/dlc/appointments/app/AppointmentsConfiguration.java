package co.edu.corhuila.dlc.appointments.app;

import co.edu.corhuila.dlc.appointments.adapter.in.http.CorrelationFilter;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.RsaPublicKeys;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.StaffAuthenticationFilter;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.StaffAuthenticationVerifier;
import co.edu.corhuila.dlc.appointments.adapter.in.http.security.StaffJwtSignatureVerifier;
import co.edu.corhuila.dlc.appointments.adapter.out.auth.AuthSessionValidationClient;
import java.net.http.HttpClient;
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

    /** No redirects and explicit deadlines: Auth answers or the request fails closed with 503. */
    private static AuthSessionValidationClient authSessions(AppointmentsProperties.Auth auth) {
        var transport = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(auth.timeout()).build();
        var requests = new JdkClientHttpRequestFactory(transport);
        requests.setReadTimeout(auth.timeout());
        return new AuthSessionValidationClient(RestClient.builder().requestFactory(requests).build(),
                auth.internalUrl(), auth::currentServiceToken);
    }
}
