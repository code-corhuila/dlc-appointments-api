package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import java.util.List;

/** Boundary validation failure carrying one detail per invalid field or header (Annex C). */
public final class RequestValidationException extends RuntimeException {
    private final List<ApiError.Detail> details;

    public RequestValidationException(List<ApiError.Detail> details) {
        super("The request is invalid.");
        this.details = List.copyOf(details);
    }

    public List<ApiError.Detail> details() {
        return details;
    }
}
