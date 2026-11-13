package dev.ucplab.service.web;

import dev.ucplab.core.id.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a UUIDv7 id from the injected {@link IdGenerator}, sent back as {@code X-Request-Id} and used as
 * the {@code request_id} of an error body (m6-plan section 3.1). A client's own {@code X-Request-Id} is never trusted.
 * It runs first, so even an error raised by a later filter carries the id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIds extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final String ATTRIBUTE = RequestIds.class.getName();

    private final IdGenerator ids;

    public RequestIds(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        idOf(request, response, ids);
        chain.doFilter(request, response);
    }

    /** The request's id, assigned now if no filter has assigned one (an error raised before the filter chain). */
    static String idOf(HttpServletRequest request, HttpServletResponse response, IdGenerator ids) {
        Object existing = request.getAttribute(ATTRIBUTE);
        if (existing instanceof String id) {
            if (!response.containsHeader(HEADER)) {
                response.setHeader(HEADER, id);
            }
            return id;
        }
        String id = ids.next();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        return id;
    }
}
