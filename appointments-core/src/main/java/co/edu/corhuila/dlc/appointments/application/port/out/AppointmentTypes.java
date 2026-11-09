package co.edu.corhuila.dlc.appointments.application.port.out;

import java.util.List;

/** HU-APT-006 (APT-014): the migration-owned appointment-type catalog. */
public interface AppointmentTypes {
    /** True when the code names an active type. */
    boolean isActive(String code);

    /** Active types ordered by sort order, then code. */
    default List<AppointmentType> listActive() {
        return List.of();
    }

    record AppointmentType(String code, String label, String color) { }
}
