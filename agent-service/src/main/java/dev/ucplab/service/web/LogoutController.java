package dev.ucplab.service.web;

import dev.ucplab.core.identity.SessionRepository;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /auth/logout} (18.3, 18.7): deletes this session's row at once and clears the cookie; 204. Needs a session
 * and CSRF like any other unsafe route. The user's other sessions stay valid (18.7). Anything in the body is ignored.
 */
@RestController
final class LogoutController {

    private final SessionRepository sessions;

    LogoutController(SessionRepository sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @PostMapping("/auth/logout")
    ResponseEntity<Void> logout(SignedIn caller) {
        sessions.delete(caller.rawSessionId());
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, SessionCookie.clear().toString()).build();
    }
}
