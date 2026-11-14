package dev.ucplab.service.web;

import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.identity.UserSession;
import java.util.Objects;

/**
 * The signed-in caller of one request, as {@link SessionInterceptor} found them: the {@link AuthContext} every core call
 * takes, the session row, and the cookie value (kept in memory for this request only, so logout can delete the session).
 * {@link #toString()} shows neither the cookie value nor the CSRF token.
 */
public record SignedIn(AuthContext auth, UserSession session, String rawSessionId) {

    static final String ATTRIBUTE = SignedIn.class.getName();

    public SignedIn {
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(rawSessionId, "rawSessionId");
    }

    @Override
    public String toString() {
        return "SignedIn[user=" + auth.user() + "]";
    }
}
