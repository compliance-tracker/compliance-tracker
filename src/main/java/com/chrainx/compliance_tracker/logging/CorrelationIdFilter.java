package com.chrainx.compliance_tracker.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

// Issue #51: no way to trace a single HTTP request's log lines across the pipeline (controller
// -> service -> repository). Every request gets a correlation ID - reused from an incoming
// X-Correlation-Id header if the caller already has one (a gateway/load balancer, or a client
// retrying and wanting to tie the retry to the original attempt), otherwise a fresh
// UUID.randomUUID(). Put into SLF4J's MDC (a thread-local map logback's pattern can read via
// %X{correlationId} - see application.properties's logging.pattern.level), so every log line
// this request's handling thread produces carries it automatically, with no need to thread an
// id parameter through every method call by hand. Also echoed back as a response header, so a
// user/client reporting an issue can hand back the exact ID to search logs for.
//
// Registered at the servlet-container level (see LoggingConfig), not via SecurityConfig's own
// filter chain - deliberately runs before Spring Security's filter entirely, so even a request
// Security rejects outright (401/403, never reaching a controller) still gets a correlation ID
// on its own log lines.
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "correlationId";
    private static final String HEADER_NAME = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // MDC is thread-local, and Tomcat reuses worker threads across requests - without the
        // finally block below, a request handled on a given thread would leak its correlation
        // ID into whatever unrelated request that same thread happens to handle next.
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
