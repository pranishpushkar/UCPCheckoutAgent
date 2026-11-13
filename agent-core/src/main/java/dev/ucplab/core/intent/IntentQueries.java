package dev.ucplab.core.intent;

import dev.ucplab.core.identity.UserId;
import java.util.List;
import java.util.Optional;

/**
 * User-facing reads of intents for the REST API (23.2). Every method takes the owner's {@link UserId} and returns only
 * that user's data; another user's intent reads exactly like a missing one (T2, T6, I9). Never cached (section 20).
 * Kept apart from {@link IntentRepository}, whose system methods serve the orchestrator and RecoveryWorker.
 */
public interface IntentQueries {

    /** The default page size of {@code GET /api/intents} (23.2). */
    int DEFAULT_LIMIT = 20;

    /** The largest page size of {@code GET /api/intents} (23.2: limit 1 to 50). */
    int MAX_LIMIT = 50;

    /**
     * The owner's intents, newest first by {@code created_at}, then by id from highest to lowest on a tie, so the order
     * is total and paging never skips or repeats an intent. {@code after} continues from the last intent of an earlier
     * page; its {@code next} is present only when more intents follow. {@code state}, when present, lists only intents
     * in that state.
     *
     * @throws IllegalArgumentException if {@code limit} is not between 1 and {@link #MAX_LIMIT}
     */
    IntentPage page(UserId owner, Optional<IntentState> state, Optional<IntentCursor> after, int limit);

    /** The intent's events in sequence order; empty when the intent does not exist or belongs to someone else (T6). */
    List<IntentEvent> events(UserId owner, IntentId id);
}
