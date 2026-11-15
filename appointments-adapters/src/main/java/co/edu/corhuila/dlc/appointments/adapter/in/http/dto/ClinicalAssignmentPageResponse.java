package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import co.edu.corhuila.dlc.appointments.application.model.AssignmentPage;
import java.util.List;

/** Contract ClinicalAssignmentPage: {data, meta} with the shared PaginatedMeta (Norma 5.3.6). */
public record ClinicalAssignmentPageResponse(List<ClinicalAssignmentResponse> data,
        AppointmentPageResponse.Meta meta) {
    public static ClinicalAssignmentPageResponse from(AssignmentPage page) {
        long totalPages = (page.total() + page.limit() - 1) / page.limit();
        return new ClinicalAssignmentPageResponse(page.items().stream().map(ClinicalAssignmentResponse::from).toList(),
                new AppointmentPageResponse.Meta(page.page(), page.limit(), page.total(), totalPages));
    }
}
