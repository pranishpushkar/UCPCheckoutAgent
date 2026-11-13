package dev.ucplab.core.intent;

import java.time.Instant;
import java.util.Objects;

/**
 * A position in the newest-first order of {@link IntentQueries#page}: the last intent of a page, by its
 * {@code created_at} and id. The next page holds the intents strictly after it in that order. A cursor that crosses the
 * API boundary is encoded and signed by {@link IntentCursorCodec}; this type is never parsed from client text directly.
 */
public record IntentCursor(Instant createdAt, IntentId id) {

    public IntentCursor {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(id, "id");
    }

    /** The cursor after {@code intent}. */
    public static IntentCursor after(PurchaseIntent intent) {
        return new IntentCursor(intent.createdAt(), intent.id());
    }

    /** True when {@code intent} comes after this position in the newest-first, id-descending order. */
    public boolean isBefore(PurchaseIntent intent) {
        int byTime = intent.createdAt().compareTo(createdAt);
        return byTime < 0 || (byTime == 0 && intent.id().value().compareTo(id.value()) < 0);
    }
}
