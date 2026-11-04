package co.edu.corhuila.dlc.appointments.adapter.in.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Norma 5.3.9: reuses a safe received X-Correlation-Id or generates one, returns it, writes it on
 * every log line through the MDC and exposes it as the trusted traceId of the error envelope.
 * The access line records method, path without query string, status and duration only.
 */
public final class CorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Logger ACCESS = LoggerFactory.getLogger("http.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = correlationId(request.getHeader(HEADER));
        request.setAttribute(HttpExceptionHandler.TRUSTED_TRACE_ID_ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            ACCESS.info("{} {} {} {}ms", request.getMethod(), request.getRequestURI(), response.getStatus(),
                    (System.nanoTime() - started) / 1_000_000);
            MDC.remove(MDC_KEY);
        }
    }

    private static String correlationId(String received) {
        return received != null && SAFE.matcher(received).matches() ? received : UUID.randomUUID().toString();
    }
}
