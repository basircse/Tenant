package com.revesoft.tms.common;

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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id and logs one line when it finishes: method, path, status, time and
 * caller. The id is put in the logging context ({@code requestId}) so every log line written while
 * handling the request carries it, and is returned in the {@code X-Request-Id} header so a client
 * error can be matched to the server log. A client may send its own id (letters, digits, dashes).
 *
 * <p>Levels: DEBUG for every request, INFO for slow ones, WARN for server errors. Bodies, headers
 * and query strings are never logged: they can hold passwords, tokens and personal data.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    /** Logging-context keys; the user ones are filled in by {@code CallerLoggingFilter}. */
    public static final String REQUEST_ID = "requestId";
    public static final String USER = "user";
    public static final String ORG = "org";

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final long slowMillis;

    public RequestLoggingFilter(@Value("${tms.logging.slow-request-ms:2000}") long slowMillis) {
        this.slowMillis = slowMillis;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString().substring(0, 13);
        MDC.put(REQUEST_ID, id);
        response.setHeader(HEADER, id);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            String line = "{} {} -> {} ({} ms)";
            Object[] args = {request.getMethod(), request.getRequestURI(), status, ms};
            if (status >= 500) {
                log.warn(line, args);
            } else if (ms >= slowMillis) {
                log.info("Slow request: " + line, args);
            } else {
                log.debug(line, args);
            }
            MDC.remove(REQUEST_ID);
            MDC.remove(USER);
            MDC.remove(ORG);
        }
    }
}
