package dev.ucplab.ucp.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Keeps a catalog source's token ahead of its calls (26.7, 20): {@link #refresh} makes sure a token is held, fetching
 * one when none is or when the held one is due, and says when to call it again: at the token's refresh time, or
 * {@link #RETRY} after a fetch that failed. The caller schedules it on its own thread, so nothing waits for it.
 */
public final class TokenRefresher {

    /** 26.7: a fetch that failed is tried again this much later. */
    public static final Duration RETRY = Duration.ofSeconds(30);
    /** The shortest wait between two refreshes, so a token due already is not fetched in a loop. */
    static final Duration FLOOR = Duration.ofSeconds(1);

    private final TokenProvider tokens;
    private final Clock clock;

    public TokenRefresher(TokenProvider tokens, Clock clock) {
        this.tokens = Objects.requireNonNull(tokens, "tokens");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Skeleton for the red tests of the catalog's startup token: fetches nothing yet. */
    public Duration refresh() {
        return RETRY;
    }

    @Override
    public String toString() {
        return "TokenRefresher[" + tokens + "]";
    }
}
