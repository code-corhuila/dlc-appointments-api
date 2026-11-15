package co.edu.corhuila.dlc.appointments.adapter.in.http.security;

import java.util.Objects;
import java.util.Set;

/** A calling service verified for this request; each internal operation checks its own scope. */
public record AuthenticatedService(String subject, Set<String> scopes) {
    public static final String ATTRIBUTE = AuthenticatedService.class.getName();

    public AuthenticatedService {
        Objects.requireNonNull(subject, "subject");
        scopes = Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
