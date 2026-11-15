package co.edu.corhuila.dlc.appointments.application.port.out;

import java.util.UUID;

/** Authoritative APT-001 checks. Unknown/failed checks must throw, never return true.
 * Adapters report dependency failures as SchedulingException(DEPENDENCY_UNAVAILABLE).
 */
public interface AppointmentEligibility {
    boolean isPatientActive(UUID patientId);
    /** True only for an active staff member with the DENTIST role. */
    boolean isDentistAuthorized(UUID dentistId);
}
