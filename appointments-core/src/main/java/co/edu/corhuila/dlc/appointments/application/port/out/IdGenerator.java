package co.edu.corhuila.dlc.appointments.application.port.out;

import java.util.UUID;

/** Source of new owner identifiers; adapters decide how UUIDs are produced. */
@FunctionalInterface
public interface IdGenerator {
    UUID newId();
}
