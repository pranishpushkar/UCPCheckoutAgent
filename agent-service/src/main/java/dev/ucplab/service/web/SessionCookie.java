package dev.ucplab.service.web;

import dev.ucplab.core.identity.UserSession;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;

/**
 * The session cookie of the local profile (18.5, 18.8): {@code ucplab_session}, {@code HttpOnly}, {@code SameSite=Lax},
 * {@code Path=/}, and no {@code Secure} or {@code __Host-} prefix because the local stack is plain HTTP. {@code prod}
 * has no sign-in in M6, so it never issues one.
 */
final class SessionCookie {

    static final String NAME = "ucplab_session";

    private SessionCookie() {
    }

    /** The cookie's value, if the request carries a non-blank one. */
    static Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies).filter(c -> NAME.equals(c.getName())).map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank()).findFirst();
    }

    static ResponseCookie issue(String rawId) {
        return base(rawId).maxAge(UserSession.ABSOLUTE_LIFETIME).build();
    }

    static ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private static ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value).httpOnly(true).secure(false).sameSite("Lax").path("/");
    }
}
