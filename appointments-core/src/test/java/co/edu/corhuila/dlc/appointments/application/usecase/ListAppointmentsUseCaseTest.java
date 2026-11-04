package co.edu.corhuila.dlc.appointments.application.usecase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.edu.corhuila.dlc.appointments.application.model.AppointmentPage;
import co.edu.corhuila.dlc.appointments.application.model.AppointmentQuery;
import co.edu.corhuila.dlc.appointments.application.model.ReaderContext;
import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentSearch;
import co.edu.corhuila.dlc.appointments.domain.model.AppointmentStatus;
import co.edu.corhuila.dlc.appointments.domain.model.SchedulingException;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** HU-APT-003 calendar scope (decision D6): authorization narrows the query before records and totals. */
class ListAppointmentsUseCaseTest {
    private static final UUID DENTIST = UUID.randomUUID();
    private AppointmentSearch.Criteria searched;
    private final ListAppointmentsUseCase useCase = new ListAppointmentsUseCase(criteria -> {
        searched = criteria;
        return new AppointmentPage(List.of(), criteria.page(), criteria.limit(), 0);
    });

    @Test
    void schedulersSearchWithTheirFiltersAndEveryStatus() {
        UUID patient = UUID.randomUUID();
        var from = OffsetDateTime.parse("2031-01-01T00:00:00-05:00");
        useCase.list(new AppointmentQuery(patient, DENTIST, from, null, 2, 50), reader(UUID.randomUUID(), "ADMINISTRATOR"));
        assertEquals(patient, searched.patientId());
        assertEquals(DENTIST, searched.dentistId());
        assertEquals(from, searched.from());
        assertNull(searched.to());
        assertEquals(EnumSet.allOf(AppointmentStatus.class), searched.statuses());
        assertEquals(2, searched.page());
        assertEquals(50, searched.limit());
        useCase.list(new AppointmentQuery(null, null, null, null, 1, 20), reader(UUID.randomUUID(), "SECRETARY_ASSISTANT"));
        assertNull(searched.dentistId());
    }

    @Test
    void dentistSeesOnlyTheirOwnPendingAndPerformedAppointments() {
        useCase.list(new AppointmentQuery(null, null, null, null, 1, 20), reader(DENTIST, "DENTIST"));
        assertEquals(DENTIST, searched.dentistId());
        assertEquals(EnumSet.of(AppointmentStatus.PROGRAMADA, AppointmentStatus.CONFIRMADA,
                AppointmentStatus.EN_ATENCION, AppointmentStatus.FINALIZADA), searched.statuses());
    }

    @Test
    void dentistAskingForAnotherDentistGetsAnEmptyPageWithoutSearching() {
        searched = null;
        AppointmentPage page = useCase.list(new AppointmentQuery(null, UUID.randomUUID(), null, null, 3, 10),
                reader(DENTIST, "DENTIST"));
        assertNull(searched);
        assertTrue(page.items().isEmpty());
        assertEquals(0, page.total());
        assertEquals(3, page.page());
        assertEquals(10, page.limit());
    }

    @Test
    void readerWithoutAStaffRoleIsForbidden() {
        assertEquals(SchedulingException.Reason.AUTHORIZATION_DENIED, assertThrows(SchedulingException.class,
                () -> useCase.list(new AppointmentQuery(null, null, null, null, 1, 20), reader(DENTIST, "PATIENT")))
                .reason());
    }

    @Test
    void pageReportsTotalPages() {
        assertEquals(0, new AppointmentPage(List.of(), 1, 20, 0).totalPages());
        assertEquals(1, new AppointmentPage(List.of(), 1, 20, 20).totalPages());
        assertEquals(2, new AppointmentPage(List.of(), 1, 20, 21).totalPages());
    }

    private static ReaderContext reader(UUID actor, String role) {
        return new ReaderContext(actor, Set.of(role));
    }
}
