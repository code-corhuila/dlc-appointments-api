package co.edu.corhuila.dlc.appointments.application.usecase;

import co.edu.corhuila.dlc.appointments.application.model.AssignedPatientsPage;
import co.edu.corhuila.dlc.appointments.application.model.CareRelationship;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentEligibility;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentReader;
import co.edu.corhuila.dlc.appointments.application.port.out.CareRelationships;
import co.edu.corhuila.dlc.appointments.domain.model.Appointment;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentNotFoundException;
import co.edu.corhuila.dlc.appointments.domain.model.DentistNotFoundException;
import java.util.Objects;
import java.util.UUID;

/** #16 reads for Clinical; the calling service's scope is checked by the inbound adapter. */
public final class InternalRelationshipReads {
    private final CareRelationships relationships;
    private final AppointmentReader appointments;
    private final AppointmentEligibility eligibility;

    public InternalRelationshipReads(CareRelationships relationships, AppointmentReader appointments,
            AppointmentEligibility eligibility) {
        this.relationships = Objects.requireNonNull(relationships, "relationships");
        this.appointments = Objects.requireNonNull(appointments, "appointments");
        this.eligibility = Objects.requireNonNull(eligibility, "eligibility");
    }

    public CareRelationship relationship(UUID patientId, UUID dentistId) {
        return relationships.between(patientId, dentistId);
    }

    /** An id that Auth does not know as an active Dentist is unknown (404); Auth unavailable is 503. */
    public AssignedPatientsPage assignedPatients(UUID dentistId, int page, int limit) {
        if (!eligibility.isDentistAuthorized(dentistId)) throw new DentistNotFoundException();
        return relationships.assignedTo(dentistId, page, limit);
    }

    public Appointment encounterStatus(UUID appointmentId) {
        return appointments.findById(appointmentId).orElseThrow(AppointmentNotFoundException::new);
    }
}
