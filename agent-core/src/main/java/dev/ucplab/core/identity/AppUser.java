package dev.ucplab.core.identity;

import java.time.Instant;
import java.util.Objects;

/**
 * A user as section 10 {@code app_user} stores them: the id from sign-in, the email, and when they were first and last
 * signed in. {@link #toString()} leaves out the email (section 22: personal data stays out of logs).
 */
public record AppUser(UserId id, String email, Instant createdAt, Instant lastLoginAt) {

    public AppUser {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastLoginAt, "lastLoginAt");
        if (email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
    }

    @Override
    public String toString() {
        return "AppUser[id=" + id + ", createdAt=" + createdAt + ", lastLoginAt=" + lastLoginAt + ']';
    }
}
