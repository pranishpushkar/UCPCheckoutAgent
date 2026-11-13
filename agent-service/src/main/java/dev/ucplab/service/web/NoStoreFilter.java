package dev.ucplab.service.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Cache-Control: no-store} on every {@code /api} and {@code /auth} response, errors included (section 20
 * [CONTRACT], m6-plan section 11): sessions, quotes, approvals and the approval token are never cached anywhere.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class NoStoreFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (applies(request.getRequestURI())) {
            response.setHeader("Cache-Control", "no-store");
        }
        chain.doFilter(request, response);
    }

    static boolean applies(String path) {
        return path.equals("/api") || path.startsWith("/api/") || path.equals("/auth") || path.startsWith("/auth/");
    }
}
