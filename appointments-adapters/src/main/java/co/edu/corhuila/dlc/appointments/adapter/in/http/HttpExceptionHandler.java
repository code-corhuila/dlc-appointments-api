package co.edu.corhuila.dlc.appointments.adapter.in.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** MVC errors only; security filters, missing routes and /error need separate verification. */
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
        if (status.value() == 400) {
            var mapped = HttpErrorMapper.invalidRequest(trustedTrace(request));
            return new ResponseEntity<>(mapped.getBody(), headers, status);
        }
        // Preserve Spring MVC's recognized statuses rather than classifying them as unexpected failures.
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    private String trustedTrace(WebRequest request) {
        Object value = request.getAttribute(TRUSTED_TRACE_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof String trace && !trace.isBlank() ? trace : null;
    }
}
