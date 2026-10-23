package co.edu.corhuila.dlc.appointments.adapter.in.http.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, List<Detail> details, String traceId) {
    public ApiError {
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(message, "message");
        details = details == null ? null : List.copyOf(details);
    }

    public record Detail(String field, String message) { }
}
