package co.edu.corhuila.dlc.appointments.adapter.in.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** MVC errors, including missing routes and unsupported methods; filters write their own envelope. */
@RestControllerAdvice
public class HttpExceptionHandler extends ResponseEntityExceptionHandler {
    /** Server-side instrumentation may set this only after verifying its trace source.
     * No header or parameter is read; this handler neither generates nor verifies trace IDs.
     */
    public static final String TRUSTED_TRACE_ID_ATTRIBUTE = HttpExceptionHandler.class.getName() + ".trustedTraceId";

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleFailure(Exception failure, WebRequest request) {
        return HttpErrorMapper.map(failure, trustedTrace(request));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // Preserve Spring MVC's recognized statuses rather than classifying them as unexpected failures.
        var mapped = ex instanceof HttpMessageNotReadableException unreadable
                ? java.util.Optional.of(HttpErrorMapper.invalidBody(unreadable.getCause(), trustedTrace(request)))
                : HttpErrorMapper.forFrameworkStatus(status.value(), trustedTrace(request));
        return mapped
                .<ResponseEntity<Object>>map(envelope -> {
                    HttpHeaders json = new HttpHeaders();
                    if (headers != null) json.putAll(headers);
                    json.setContentType(MediaType.APPLICATION_JSON);
                    return new ResponseEntity<>(envelope, json, status);
                })
                .orElseGet(() -> super.handleExceptionInternal(ex, body, headers, status, request));
    }

    private String trustedTrace(WebRequest request) {
        Object value = request.getAttribute(TRUSTED_TRACE_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof String trace && !trace.isBlank() ? trace : null;
    }
}
