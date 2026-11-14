package dev.ucplab.core.identity;

import java.util.Optional;

/**
 * Storage port for sign-in sessions (18.5 [CONTRACT], section 10 {@code user_session}). Callers pass the cookie value;
 * the adapter looks it up by {@link UserSession#hashOf its hash} and never stores the value itself. Expiry is judged on
 * the adapter's injected clock. Never cached (section 20).
 */
public interface SessionRepository {

    /** @throws IllegalArgumentException if a session with the same id hash exists */
    void insert(UserSession session);

    /** The session for the cookie value {@code rawId}, if it exists and is {@link UserSession#isLive live} now. */
    Optional<UserSession> findLive(String rawId);

    /** Sets {@code last_seen_at} to now for a live session. False, and nothing written, when it is not live. */
    boolean touch(String rawId);

    /** Deletes the session for the cookie value (logout, 18.7). False when there was none. */
    boolean delete(String rawId);

    /** Deletes every session of {@code user} (18.7) and returns how many there were. */
    int deleteAllFor(UserId user);
}
