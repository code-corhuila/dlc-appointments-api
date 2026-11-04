package co.edu.corhuila.dlc.appointments.adapter.in.http;

/** True only when this service's own dependencies needed to serve requests answer (its database). */
@FunctionalInterface
public interface ReadinessProbe {
    boolean ready();
}
