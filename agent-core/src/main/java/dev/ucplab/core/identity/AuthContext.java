package dev.ucplab.core.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Built only by agent-service from a verified OIDC token. Core code never reads HTTP.
 * {@code authTime} is the token's auth_time claim, never its issue time or a refresh time (T4). Appendix B.
 */
public record AuthContext(UserId user, String email, Instant authTime, String sessionId) {

    public AuthContext {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(authTime, "authTime");
        Objects.requireNonNull(sessionId, "sessionId");
    }

    /** True while the sign-in is no older than {@code maxAge} (T4). */
    public boolean isRecent(Clock clock, Duration maxAge) {
        return !authTime.plus(maxAge).isBefore(clock.instant());
    }

    /** The email is personal data and the session id is a credential reference; keep both out of logs. */
    @Override
    public String toString() {
        return "AuthContext[user=" + user + ", authTime=" + authTime + ']';
    }
}
