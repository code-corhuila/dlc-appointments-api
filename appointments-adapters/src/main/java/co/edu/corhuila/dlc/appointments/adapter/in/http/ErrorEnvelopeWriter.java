package co.edu.corhuila.dlc.appointments.adapter.in.http;

import co.edu.corhuila.dlc.appointments.adapter.in.http.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

/** Writes the common envelope from servlet filters, which run outside Spring MVC's exception handling. */
public final class ErrorEnvelopeWriter {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ErrorEnvelopeWriter() { }

    public static void write(HttpServletRequest request, HttpServletResponse response, int status,
            String code, String message) throws IOException {
        Object trace = request.getAttribute(HttpExceptionHandler.TRUSTED_TRACE_ID_ATTRIBUTE);
        String traceId = trace instanceof String value && !value.isBlank() ? value : null;
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getOutputStream(), new ApiError(code, message, null, traceId));
    }
}
