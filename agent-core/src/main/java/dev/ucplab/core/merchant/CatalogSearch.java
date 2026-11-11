package dev.ucplab.core.merchant;

import java.util.Objects;

/** A free-text catalog search. {@code limit} is a request, not a promise: the merchant may return fewer (6.2). */
public record CatalogSearch(String query, int limit) {

    public static final int MAX_LIMIT = 10;

    public CatalogSearch {
        Objects.requireNonNull(query, "query");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1 to " + MAX_LIMIT);
        }
    }
}
