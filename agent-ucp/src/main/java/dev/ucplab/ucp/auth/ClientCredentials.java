package dev.ucplab.ucp.auth;

import java.util.Objects;

/**
 * A merchant's client credentials (26.4): read from the environment at startup, sent only to the token endpoint, never
 * written or logged (I8). {@link #toString()} shows neither value.
 */
public record ClientCredentials(String clientId, String clientSecret) {

    public ClientCredentials {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalArgumentException("client credentials must not be blank");
        }
    }

    @Override
    public String toString() {
        return "ClientCredentials[***]";
    }
}
