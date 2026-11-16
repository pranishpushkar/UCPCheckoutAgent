package dev.ucplab.core.merchant;

import java.util.Objects;

/**
 * The merchant's checkout session id. Immutable for an intent once a complete attempt exists (I4). A merchant's id may
 * carry an access key ({@code gid://shopify/Checkout/<cart>?key=...}), so {@link #toString()} prints nothing from the
 * first {@code ?} on (I8, 10); {@link #value()} is the id as the merchant sent it, for the merchant only.
 */
public record SessionId(String value) {

    public SessionId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("SessionId must not be blank");
        }
    }

    @Override
    public String toString() {
        int query = value.indexOf('?');
        return query < 0 ? value : value.substring(0, query + 1) + "[redacted]";
    }
}
