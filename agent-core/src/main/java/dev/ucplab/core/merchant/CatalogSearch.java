package dev.ucplab.core.merchant;

import java.util.Objects;
import java.util.Optional;

/**
 * A free-text catalog search. {@code limit} is a request, not a promise: the merchant may return fewer (6.2).
 * {@code maxPrice} is the run's budget (23.1), set in code and never from the model: a merchant that reads a price filter
 * is asked for nothing above it, and the tool drops whatever is above it anyway (6.2).
 */
public record CatalogSearch(String query, int limit, Optional<Money> maxPrice) {

    public static final int MAX_LIMIT = 10;

    /** A search with no price limit. */
    public CatalogSearch(String query, int limit) {
        this(query, limit, Optional.empty());
    }

    public CatalogSearch {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(maxPrice, "maxPrice");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1 to " + MAX_LIMIT);
        }
    }
}
