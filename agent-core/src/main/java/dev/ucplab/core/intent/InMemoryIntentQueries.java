package dev.ucplab.core.intent;

import dev.ucplab.core.identity.UserId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** {@link IntentQueries} over an {@link InMemoryIntentRepository}: the same order and paging as the SQL adapter. */
public final class InMemoryIntentQueries implements IntentQueries {

    /** Newest first, then the id from highest to lowest (the SQL adapter's {@code ORDER BY}). */
    private static final Comparator<PurchaseIntent> NEWEST_FIRST = Comparator
            .comparing(PurchaseIntent::createdAt).thenComparing(i -> i.id().value()).reversed();

    private final InMemoryIntentRepository intents;

    public InMemoryIntentQueries(InMemoryIntentRepository intents) {
        this.intents = Objects.requireNonNull(intents, "intents");
    }

    @Override
    public IntentPage page(UserId owner, Optional<IntentState> state, Optional<IntentCursor> after, int limit) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(after, "after");
        IntentPage.checkLimit(limit);
        List<PurchaseIntent> matching = intents.ownedBy(owner).stream()
                .filter(i -> state.map(s -> i.state() == s).orElse(true))
                .filter(i -> after.map(c -> c.isBefore(i)).orElse(true))
                .sorted(NEWEST_FIRST)
                .limit(limit + 1L)
                .toList();
        if (matching.size() <= limit) {
            return new IntentPage(matching, Optional.empty());
        }
        List<PurchaseIntent> page = matching.subList(0, limit);
        return new IntentPage(page, Optional.of(IntentCursor.after(page.get(limit - 1))));
    }

    @Override
    public List<IntentEvent> events(UserId owner, IntentId id) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(id, "id");
        return intents.find(owner, id).isPresent() ? intents.events(id) : List.of();
    }
}
