package dev.ucplab.core.identity;

import dev.ucplab.core.crypto.Sha256;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One signed-in session, as section 10 {@code user_session} stores it (18.5 [CONTRACT]). It holds the SHA-256 hash of the
 * cookie value, never the value itself, so a copy of the table cannot be replayed as cookies. {@code authTime} is when
 * the user signed in (18.6 reads it for recent sign-in, T4). {@link #toString()} leaves out the hash and the CSRF token.
 */
public record UserSession(
        String idHash,
        UserId user,
        String csrfToken,
        Instant authTime,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt) {

    /** 18.5: a session idle this long is over. */
    public static final Duration IDLE_TIMEOUT = Duration.ofHours(12);

    /** 18.5: a session is over this long after it was created, however active. */
    public static final Duration ABSOLUTE_LIFETIME = Duration.ofDays(7);

    public UserSession {
        Objects.requireNonNull(idHash, "idHash");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(csrfToken, "csrfToken");
        Objects.requireNonNull(authTime, "authTime");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (idHash.isBlank() || csrfToken.isBlank()) {
            throw new IllegalArgumentException("a session needs an id hash and a CSRF token");
        }
    }

    /**
     * A new session for the cookie value {@code rawId}, created and last seen at {@code now}, ending at the latest
     * {@link #ABSOLUTE_LIFETIME} later. The raw value is hashed here and not kept.
     */
    public static UserSession open(String rawId, UserId user, String csrfToken, Instant authTime, Instant now) {
        Objects.requireNonNull(now, "now");
        return new UserSession(hashOf(rawId), user, csrfToken, authTime, now, now, now.plus(ABSOLUTE_LIFETIME));
    }

    /** The stored form of a cookie value: lowercase hex SHA-256 (18.5). */
    public static String hashOf(String rawId) {
        Objects.requireNonNull(rawId, "rawId");
        if (rawId.isBlank()) {
            throw new IllegalArgumentException("a session id must not be blank");
        }
        return Sha256.hex(rawId);
    }

    /** Live at {@code now}: less than {@link #IDLE_TIMEOUT} since last seen, and before {@code expiresAt}. */
    public boolean isLive(Instant now) {
        Objects.requireNonNull(now, "now");
        return now.isBefore(lastSeenAt.plus(IDLE_TIMEOUT)) && now.isBefore(expiresAt);
    }

    public UserSession seenAt(Instant at) {
        return new UserSession(idHash, user, csrfToken, authTime, createdAt, at, expiresAt);
    }

    @Override
    public String toString() {
        return "UserSession[user=" + user + ", authTime=" + authTime + ", createdAt=" + createdAt + ", lastSeenAt="
                + lastSeenAt + ", expiresAt=" + expiresAt + ']';
    }
}
