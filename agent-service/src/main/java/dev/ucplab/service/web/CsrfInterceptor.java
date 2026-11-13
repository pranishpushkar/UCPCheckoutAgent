package dev.ucplab.service.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The synchronizer-token check (18.5): every POST, PUT, PATCH and DELETE that needs a session must carry the session's
 * token as {@code X-CSRF-Token}, compared in constant time. Missing or wrong is 403 {@code csrf_failed}. Runs after
 * {@link SessionInterceptor}, so a request without a session is 401 first.
 */
public final class CsrfInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-CSRF-Token";
    private static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!UNSAFE.contains(request.getMethod())) {
            return true;
        }
        if (!(request.getAttribute(SignedIn.ATTRIBUTE) instanceof SignedIn signedIn)) {
            throw ApiException.of(ErrorCode.UNAUTHENTICATED);
        }
        String sent = request.getHeader(HEADER);
        if (sent == null || !MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8),
                signedIn.session().csrfToken().getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.of(ErrorCode.CSRF_FAILED);
        }
        return true;
    }
}
