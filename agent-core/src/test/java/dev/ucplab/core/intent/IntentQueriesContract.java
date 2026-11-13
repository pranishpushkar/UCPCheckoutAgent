package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.MerchantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every {@link IntentQueries} must have (23.2, T2, T6, I9): the caller's intents only, newest first, in a
 * stable order on a tie, a bounded limit, and a cursor that continues where the last page stopped. One suite, run
 * against the in-memory adapter and the SQL adapter.
 */
public abstract class IntentQueriesContract {

    protected static final Instant T0 = Instant.parse("2026-01-01T00:00:00.123456789Z");
    protected static final UserId ALICE = new UserId("alice");
    protected static final UserId BOB = new UserId("bob");
    private static final MerchantId SHOP = new MerchantId("shop-1");

    /** The write side the intents are stored through, and the read port under test, over one store. */
    public record Stores(IntentRepository intents, IntentQueries queries) {}

    protected final MutableClock clock = new MutableClock(T0);
    protected IntentRepository intents;
    protected IntentQueries queries;

    /** Fresh, empty stores over one backing store, reading time from {@code clock}. */
    protected abstract Stores create(Clock clock);

    @BeforeEach
    void createStores() {
        Stores stores = create(clock);
        intents = stores.intents();
        queries = stores.queries();
    }

    private PurchaseIntent store(String id, UserId owner, IntentState state, Instant createdAt) {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId(id), owner, SHOP, "dedupe-" + id,
                List.of(new ProposedLine("item_123", 1)), owner.value() + "@example.com", createdAt)
                .withState(state, createdAt);
        intents.insert(intent);
        return intent;
    }

    private static List<String> ids(IntentPage page) {
        return page.intents().stream().map(i -> i.id().value()).toList();
    }

    // Covers: 23.2, T2 (the caller's intents, newest first)
    @Test
    void page_returnsTheOwnersIntentsNewestFirst() {
        store("a1", ALICE, IntentState.COMPLETED, T0);
        store("a2", ALICE, IntentState.AWAITING_APPROVAL, T0.plusSeconds(1));
        store("a3", ALICE, IntentState.REJECTED, T0.plusSeconds(2));

        IntentPage page = queries.page(ALICE, Optional.empty(), Optional.empty(), 20);

        assertThat(ids(page)).containsExactly("a3", "a2", "a1");
        assertThat(page.next()).isEmpty();
    }

    // Covers: T6, I9 (another user's intents are not listed, and a user with none reads an empty page)
    @Test
    void page_neverListsAnotherUsersIntents() {
        store("a1", ALICE, IntentState.COMPLETED, T0);
        store("b1", BOB, IntentState.COMPLETED, T0.plusSeconds(1));

        assertThat(ids(queries.page(ALICE, Optional.empty(), Optional.empty(), 50))).containsExactly("a1");
        assertThat(ids(queries.page(new UserId("carol"), Optional.empty(), Optional.empty(), 50))).isEmpty();
    }

    // Covers: 23.2 (a tie on created_at has a stable order: the id, highest first, so paging never skips or repeats)
    @Test
    void page_tieOnCreatedAt_isOrderedByIdDescending() {
        store("t-b", ALICE, IntentState.COMPLETED, T0);
        store("t-c", ALICE, IntentState.COMPLETED, T0);
        store("t-a", ALICE, IntentState.COMPLETED, T0);

        IntentPage first = queries.page(ALICE, Optional.empty(), Optional.empty(), 2);
        IntentPage second = queries.page(ALICE, Optional.empty(), first.next(), 2);

        assertThat(ids(first)).containsExactly("t-c", "t-b");
        assertThat(first.next()).contains(new IntentCursor(T0, new IntentId("t-b")));
        assertThat(ids(second)).containsExactly("t-a");
        assertThat(second.next()).isEmpty();
    }

    // Covers: 23.2 (walking the pages with the cursor visits every intent once, in order)
    @Test
    void page_cursorWalk_visitsEveryIntentOnceInOrder() {
        for (int i = 0; i < 7; i++) {
            // Pairs share a created_at (w0 and w1, w2 and w3, w4 and w5), so pages of 3 end inside a tie.
            store("w" + i, ALICE, IntentState.COMPLETED, T0.plusSeconds(i / 2));
        }
        store("other", BOB, IntentState.COMPLETED, T0.plusSeconds(1));

        List<String> walked = new java.util.ArrayList<>();
        Optional<IntentCursor> cursor = Optional.empty();
        int pages = 0;
        do {
            IntentPage page = queries.page(ALICE, Optional.empty(), cursor, 3);
            walked.addAll(ids(page));
            cursor = page.next();
            pages++;
        } while (cursor.isPresent() && pages < 10);

        assertThat(walked).containsExactly("w6", "w5", "w4", "w3", "w2", "w1", "w0");
        assertThat(pages).isEqualTo(3);
    }

    // Covers: 23.2 (a page exactly full of the last intents has no next cursor: no empty trailing page)
    @Test
    void page_lastPageExactlyFull_hasNoNext() {
        store("x1", ALICE, IntentState.COMPLETED, T0);
        store("x2", ALICE, IntentState.COMPLETED, T0.plusSeconds(1));

        IntentPage page = queries.page(ALICE, Optional.empty(), Optional.empty(), 2);

        assertThat(ids(page)).containsExactly("x2", "x1");
        assertThat(page.next()).isEmpty();
    }

    // Covers: 23.2 (the state filter lists only intents in that state, and pages within it)
    @Test
    void page_stateFilter_listsOnlyThatState() {
        store("s1", ALICE, IntentState.COMPLETED, T0);
        store("s2", ALICE, IntentState.AWAITING_APPROVAL, T0.plusSeconds(1));
        store("s3", ALICE, IntentState.COMPLETED, T0.plusSeconds(2));
        store("s4", BOB, IntentState.COMPLETED, T0.plusSeconds(3));

        IntentPage first = queries.page(ALICE, Optional.of(IntentState.COMPLETED), Optional.empty(), 1);
        IntentPage second = queries.page(ALICE, Optional.of(IntentState.COMPLETED), first.next(), 1);

        assertThat(ids(first)).containsExactly("s3");
        assertThat(ids(second)).containsExactly("s1");
        assertThat(second.next()).isEmpty();
    }

    // Covers: 23.2 (limit 1 to 50: zero, a negative and 51 are refused before anything is read)
    @Test
    void page_limitOutOfBounds_isRefused() {
        assertThatThrownBy(() -> queries.page(ALICE, Optional.empty(), Optional.empty(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queries.page(ALICE, Optional.empty(), Optional.empty(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> queries.page(ALICE, Optional.empty(), Optional.empty(), IntentQueries.MAX_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(queries.page(ALICE, Optional.empty(), Optional.empty(), IntentQueries.MAX_LIMIT).intents()).isEmpty();
        assertThat(queries.page(ALICE, Optional.empty(), Optional.empty(), 1).intents()).isEmpty();
    }

    // Covers: 23.2 (a page of 50 holds 50 when there are more, and the cursor carries on to the rest)
    @Test
    void page_maxLimit_returnsFiftyAndACursor() {
        for (int i = 0; i < 55; i++) {
            store(String.format("m%02d", i), ALICE, IntentState.COMPLETED, T0.plusMillis(i));
        }

        IntentPage first = queries.page(ALICE, Optional.empty(), Optional.empty(), IntentQueries.MAX_LIMIT);
        IntentPage rest = queries.page(ALICE, Optional.empty(), first.next(), IntentQueries.MAX_LIMIT);

        assertThat(first.intents()).hasSize(50);
        assertThat(ids(first).get(0)).isEqualTo("m54");
        assertThat(ids(rest)).containsExactly("m04", "m03", "m02", "m01", "m00");
        assertThat(rest.next()).isEmpty();
    }

    // Covers: 23.2 (the page reads the current state: an intent that moved since it was inserted is listed as it is now)
    @Test
    void page_readsTheCurrentState() {
        PurchaseIntent stored = store("c1", ALICE, IntentState.QUOTING, T0);
        Instant later = T0.plus(Duration.ofSeconds(5));
        intents.claim(stored.id(), "worker-q", Duration.ofSeconds(30));
        intents.update(intents.findForSystem(stored.id()).orElseThrow().withState(IntentState.AWAITING_APPROVAL, later),
                0, "worker-q");

        assertThat(queries.page(ALICE, Optional.empty(), Optional.empty(), 20).intents())
                .singleElement().satisfies(i -> assertThat(i.state()).isEqualTo(IntentState.AWAITING_APPROVAL));
    }

    // Covers: 23.2, T2 (the owner reads the intent's events in order)
    @Test
    void events_ownerReadsTheLogInOrder() {
        store("e1", ALICE, IntentState.QUOTING, T0);
        intents.appendEvent(new IntentId("e1"), new IntentEvent("proposed", "{\"n\":1}", T0));
        intents.appendEvent(new IntentId("e1"), new IntentEvent("policy", "{\"n\":2}", T0.plusSeconds(1)));
        intents.appendEvent(new IntentId("e1"), new IntentEvent("transition", "{\"n\":3}", T0.plusSeconds(1)));

        assertThat(queries.events(ALICE, new IntentId("e1"))).extracting(IntentEvent::type)
                .containsExactly("proposed", "policy", "transition");
        assertThat(queries.events(ALICE, new IntentId("e1")).get(0))
                .isEqualTo(new IntentEvent("proposed", "{\"n\":1}", T0));
    }

    // Covers: T6, I9 (another user's intent has no events for the caller, exactly like a missing intent)
    @Test
    void events_anotherUsersIntent_readsAsEmpty() {
        store("e2", ALICE, IntentState.QUOTING, T0);
        intents.appendEvent(new IntentId("e2"), new IntentEvent("proposed", "{}", T0));

        assertThat(queries.events(BOB, new IntentId("e2"))).isEmpty();
        assertThat(queries.events(ALICE, new IntentId("missing"))).isEmpty();
    }
}
