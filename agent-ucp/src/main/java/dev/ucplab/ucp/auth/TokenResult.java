package dev.ucplab.ucp.auth;

import java.util.Objects;

/** What {@link TokenProvider#current} returns: a token, or why none could be had (a fixed text, never a body). */
public sealed interface TokenResult {

    record Ok(AccessToken token) implements TokenResult {
        public Ok {
            Objects.requireNonNull(token, "token");
        }
    }

    /** No token: a call that needs one is not sent. {@code reason} is a fixed text with no secret, token or body. */
    record Failed(String reason) implements TokenResult {
        public Failed {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
