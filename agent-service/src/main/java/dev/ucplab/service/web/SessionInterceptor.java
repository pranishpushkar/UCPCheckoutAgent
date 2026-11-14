package dev.ucplab.service.web;

import dev.ucplab.core.identity.AppUser;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.SessionRepository;
import dev.ucplab.core.identity.UserRepository;
import dev.ucplab.core.identity.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Finds the caller of every {@code /api} request and of logout (18.5, m6-plan section 4). It runs after handler mapping,
 * so a route that does not exist is 404 with or without a session. The session is looked up by the cookie's hash; a
 * missing, expired or idle session, or one whose user is gone, is 401 {@code unauthenticated}.
 *
 * <p>This is the only service code that builds an {@link AuthContext} (ArchitectureTest), so the user id always comes
 * from the session and never from a request (T1). {@code last_seen_at} is refreshed at most once a minute (18.5).
 */
public final class SessionInterceptor implements HandlerInterceptor {

    /** 18.5: {@code last_seen_at} is refreshed at most this often. */
    static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    private final SessionRepository sessions;
    private final UserRepository users;
    private final Clock clock;

    public SessionInterceptor(SessionRepository sessions, UserRepository users, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.users = Objects.requireNonNull(users, "users");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String raw = SessionCookie.read(request).orElseThrow(() -> ApiException.of(ErrorCode.UNAUTHENTICATED));
        UserSession session = sessions.findLive(raw).orElseThrow(() -> ApiException.of(ErrorCode.UNAUTHENTICATED));
        Instant now = clock.instant();
        if (Duration.between(session.lastSeenAt(), now).compareTo(TOUCH_INTERVAL) >= 0) {
            sessions.touch(raw);
        }
        AppUser user = users.find(session.user()).orElseThrow(() -> ApiException.of(ErrorCode.UNAUTHENTICATED));
        AuthContext auth = new AuthContext(session.user(), user.email(), session.authTime(), session.idHash());
        request.setAttribute(SignedIn.ATTRIBUTE, new SignedIn(auth, session, raw));
        return true;
    }
}
