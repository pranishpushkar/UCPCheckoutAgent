package dev.ucplab.ucp.auth;

import java.util.List;
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

    /** The fixed reason for a value that does not split; it never holds the value (I8). */
    public static final String MALFORMED = "the credential_ref value is not client_id:client_secret";

    /**
     * The value of a {@code credential_ref} variable (26.4): {@code client_id:client_secret}, split at the first colon,
     * so the client id holds no colon. Both parts must be non-blank.
     *
     * @throws IllegalArgumentException with {@link #MALFORMED}, which never echoes the value
     */
    public static ClientCredentials parse(String value) {
        Objects.requireNonNull(value, "value");
        int colon = value.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException(MALFORMED);
        }
        String id = value.substring(0, colon);
        String secret = value.substring(colon + 1);
        if (id.isBlank() || secret.isBlank()) {
            throw new IllegalArgumentException(MALFORMED);
        }
        return new ClientCredentials(id, secret);
    }

    /** What a redactor masks (26.4): the whole variable value, then the secret on its own. */
    public List<String> redactable() {
        return List.of(clientId + ":" + clientSecret, clientSecret);
    }

    @Override
    public String toString() {
        return "ClientCredentials[***]";
    }
}
