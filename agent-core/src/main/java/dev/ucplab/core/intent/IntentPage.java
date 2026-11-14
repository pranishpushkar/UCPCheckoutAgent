package dev.ucplab.core.intent;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One page of {@link IntentQueries#page}: the intents in order, and where the next page starts if there is one. */
public record IntentPage(List<PurchaseIntent> intents, Optional<IntentCursor> next) {

    public IntentPage {
        intents = List.copyOf(Objects.requireNonNull(intents, "intents"));
        Objects.requireNonNull(next, "next");
    }

    /**
     * The 23.2 limit check the adapters share.
     *
     * @throws IllegalArgumentException if {@code limit} is not between 1 and {@link IntentQueries#MAX_LIMIT}
     */
    public static int checkLimit(int limit) {
        if (limit < 1 || limit > IntentQueries.MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be 1 to " + IntentQueries.MAX_LIMIT + ": " + limit);
        }
        return limit;
    }
}
