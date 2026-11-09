package co.edu.corhuila.dlc.appointments.adapter.in.http;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.edu.corhuila.dlc.appointments.application.port.out.AppointmentTypes;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** GET /api/v1/appointment-types (HU-APT-006): the active catalog, as {data: [{code, label, color}]}. */
class AppointmentTypeControllerTest {
    @Test
    void listsTheActiveCatalogInItsOrder() {
        AppointmentTypes catalog = new AppointmentTypes() {
            public boolean isActive(String code) { return true; }
            public List<AppointmentType> listActive() {
                return List.of(new AppointmentType("CONSULTA", "Consulta general", "#2563EB"),
                        new AppointmentType("LIMPIEZA", "Limpieza", "#0D9488"));
            }
        };
        var body = new AppointmentTypeController(catalog).list();
        assertEquals(Map.of("data", List.of(
                Map.of("code", "CONSULTA", "label", "Consulta general", "color", "#2563EB"),
                Map.of("code", "LIMPIEZA", "label", "Limpieza", "color", "#0D9488"))), body);
    }
}
