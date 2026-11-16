package dev.ucplab.ucp.auth;

import java.time.Instant;
import java.util.Objects;

/**
 * A merchant access token and when it ends (20): held in memory only, never written or logged (I8). The value leaves
 * this class only as the {@code Authorization} header value; {@link #toString()} shows the expiry alone. Identity, not
 * value, is what {@link TokenProvider#invalidate} compares, so equality is the default.
 */
public final class AccessToken {

    private final String value;
    private final Instant expiresAt;
    private final Instant refreshAt;

    AccessToken(String value, Instant expiresAt, Instant refreshAt) {
        this.value = Objects.requireNonNull(value, "value");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.refreshAt = Objects.requireNonNull(refreshAt, "refreshAt");
        if (value.isBlank()) {
            throw new IllegalArgumentException("an access token must not be blank");
        }
    }

    /** {@code Bearer <token>}, for the {@code Authorization} header of a call to the store. */
    public String authorizationHeader() {
        return "Bearer " + value;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /** When a caller starts fetching the next token: before {@link #expiresAt}, by the provider's margin. */
    Instant refreshAt() {
        return refreshAt;
    }

    @Override
    public String toString() {
        return "AccessToken[***, expiresAt=" + expiresAt + "]";
    }
}
