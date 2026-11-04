package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import co.edu.corhuila.dlc.appointments.application.model.AppointmentPage;
import java.util.List;

/** Contract AppointmentPage: {data, meta} with the shared PaginatedMeta (Norma 5.3.6). */
public record AppointmentPageResponse(List<AppointmentResponse> data, Meta meta) {
    public record Meta(int page, int limit, long total, long totalPages) { }

    public static AppointmentPageResponse from(AppointmentPage page) {
        return new AppointmentPageResponse(page.items().stream().map(AppointmentResponse::from).toList(),
                new Meta(page.page(), page.limit(), page.total(), page.totalPages()));
    }
}
