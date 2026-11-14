package dev.ucplab.service.web;

import dev.ucplab.core.id.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One log line per request (m6-plan section 10): the method, the path <em>template</em> ({@code /api/intents/{id}}, so
 * no id and no value from the URL), the status, the duration, and the user id when the caller is signed in. Never the
 * query string, a header, a cookie or a body (I8). The request id is in the MDC for every line logged meanwhile.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public final class RequestLogFilter extends OncePerRequestFilter {

    static final String UNMATCHED = "unmatched";
    private static final Logger LOG = LoggerFactory.getLogger("dev.ucplab.request");
    private static final String REQUEST_ID = "request_id";

    private final IdGenerator ids;

    public RequestLogFilter(IdGenerator ids) {
        this.ids = java.util.Objects.requireNonNull(ids, "ids");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        String requestId = RequestIds.idOf(request, response, ids);
        MDC.put(REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            long tookMs = (System.nanoTime() - start) / 1_000_000;
            Object matched = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String user = request.getAttribute(SignedIn.ATTRIBUTE) instanceof SignedIn s
                    ? s.auth().user().value() : null;
            LOG.info(line(request.getMethod(), matched instanceof String t ? t : UNMATCHED, response.getStatus(),
                    tookMs, user));
            MDC.remove(REQUEST_ID);
        }
    }

    /** The whole line: nothing else from the request is ever added to it. */
    static String line(String method, String template, int status, long tookMs, String user) {
        return "request method=" + method + " path=" + template + " status=" + status + " took_ms=" + tookMs
                + (user == null ? "" : " user=" + user);
    }
}
