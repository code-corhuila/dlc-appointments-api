package co.edu.corhuila.dlc.appointments.adapter.out.auth;

/** Dependency/protocol failure, never a confirmed inactive session. Contains no remote cause. */
public final class AuthSessionValidationUnavailableException extends RuntimeException {
    public AuthSessionValidationUnavailableException() {
        super("Auth session validation is unavailable.");
    }
}