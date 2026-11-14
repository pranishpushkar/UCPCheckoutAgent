package dev.ucplab.core.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.SessionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The behaviour every {@link ApprovalRepository} must have (9.2, T2, T6, I10): one suite, run against the in-memory
 * adapter and the SQL adapter.
 */
public abstract class ApprovalRepositoryContract {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00.123456789Z");
    private static final UserId ALICE = new UserId("alice");
    private static final UserId BOB = new UserId("bob");
    private static final IntentId I1 = new IntentId("i1");
    private static final IntentId I2 = new IntentId("i2");

    protected ApprovalRepository repo;

    /** A fresh, empty repository. */
    protected abstract ApprovalRepository create();

    /** Stores the parent intent of an approval, owned by {@code owner}: an approval always belongs to an intent. */
    protected abstract void storeIntent(IntentId id, UserId owner);

    private final java.util.Set<IntentId> stored = new java.util.HashSet<>();

    /** Inserts the approval, storing its parent intent first (once per intent). */
    private void put(ApprovalRecord approval) {
        if (stored.add(approval.intentId())) {
            storeIntent(approval.intentId(), approval.owner());
        }
        repo.insert(approval);
    }

    @BeforeEach
    void createRepository() {
        stored.clear();
        repo = create();
    }

    /** Two lines in an order the canonical form would change, so a round trip must keep the order it was given. */
    private static ApprovalRecord approval(String id, IntentId intent, UserId owner) {
        ApprovalSnapshot snapshot = new ApprovalSnapshot(intent, new MerchantId("shop-1"), new SessionId("s-" + id),
                List.of(new SnapshotLine("item_456", 1, 1200), new SnapshotLine("item_123", 2, 2500)), "USD", 6200,
                6200, T0.plusSeconds(600));
        return new ApprovalRecord(id, intent, owner, snapshot, snapshot.hash(), "token-hash-" + id, snapshot.expiresAt(),
                ApprovalStatus.PENDING, Optional.empty(), Optional.empty());
    }

    // Covers: T2, T6 (the owner reads the approval back exactly as it was stored)
    @Test
    void findLatest_returnsStoredRecord_forItsOwner() {
        ApprovalRecord a = approval("a1", I1, ALICE);
        put(a);

        assertThat(repo.findLatest(ALICE, I1)).contains(a);
        assertThat(repo.findLatestForSystem(I1)).contains(a);
    }

    // Covers: 9.2, D4 (a snapshot with a shipping part reads back equal, on every adapter: option, label, price and the
    // destination's country survive, and the hash stored is the one of the snapshot with shipping)
    @Test
    void findLatest_returnsTheShippingPartOfTheSnapshot() {
        ApprovalSnapshot plain = approval("a1", I1, ALICE).snapshot();
        ApprovalSnapshot shipped = new ApprovalSnapshot(plain.intentId(), plain.merchantId(), plain.sessionId(), plain.lines(),
                plain.currency(), 7700, 7700, plain.expiresAt(),
                Optional.of(new FulfillmentSnapshot("exp-ship-us", "Express Shipping (US)", 1500, Optional.of("US"))));
        ApprovalRecord a = new ApprovalRecord("a1", I1, ALICE, shipped, shipped.hash(), "token-hash-a1", shipped.expiresAt(),
                ApprovalStatus.PENDING, Optional.empty(), Optional.empty());
        put(a);

        assertThat(repo.findLatest(ALICE, I1)).contains(a);
        assertThat(repo.findLatest(ALICE, I1).orElseThrow().snapshot().fulfillment()).isPresent();
        assertThat(repo.findLatest(ALICE, I1).orElseThrow().snapshotHash()).isEqualTo(shipped.hash());
    }

    // Covers: T6 (another user gets empty, the same as for an intent with no approval)
    @Test
    void findLatest_returnsEmpty_forAnotherUserAndForUnknownIntent() {
        put(approval("a1", I1, ALICE));

        assertThat(repo.findLatest(BOB, I1)).isEmpty();
        assertThat(repo.findLatest(ALICE, I2)).isEmpty();
        assertThat(repo.findLatestForSystem(I2)).isEmpty();
    }

    // Covers: 9.2, S16 (the newest approval of an intent is the last one inserted; the list is oldest first)
    @Test
    void findLatestAndList_followInsertionOrder_perIntent() {
        ApprovalRecord first = approval("a1", I1, ALICE);
        ApprovalRecord other = approval("a2", I2, ALICE);
        ApprovalRecord second = approval("a3", I1, ALICE);
        put(first);
        put(other);
        put(second);

        assertThat(repo.listForSystem(I1)).containsExactly(first, second);
        assertThat(repo.findLatestForSystem(I1)).contains(second);
        assertThat(repo.listForSystem(I2)).containsExactly(other);
    }

    // Covers: 9.2 (an approval id is unique)
    @Test
    void insert_throws_whenIdRepeats() {
        put(approval("a1", I1, ALICE));

        assertThatThrownBy(() -> put(approval("a1", I2, BOB))).isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.listForSystem(I2)).isEmpty();
    }

    // Covers: 9.2, I10 (a decision records when and by whom)
    @Test
    void transition_movesStatusAndRecordsDecision_whenStatusMatches() {
        put(approval("a1", I1, ALICE));

        boolean moved = repo.transition("a1", ApprovalStatus.PENDING, ApprovalStatus.APPROVED, T0.plusSeconds(5),
                Optional.of(ALICE));

        assertThat(moved).isTrue();
        ApprovalRecord stored = repo.findLatestForSystem(I1).orElseThrow();
        assertThat(stored.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(stored.decidedAt()).contains(T0.plusSeconds(5));
        assertThat(stored.decidedBy()).contains(ALICE);
    }

    // Covers: 9.2 (compare-and-set: a status that is not the expected one changes nothing)
    @Test
    void transition_returnsFalseAndChangesNothing_whenStatusDiffers() {
        ApprovalRecord a = approval("a1", I1, ALICE);
        put(a);

        assertThat(repo.transition("a1", ApprovalStatus.APPROVED, ApprovalStatus.CONSUMED, T0, Optional.empty()))
                .isFalse();
        assertThat(repo.transition("missing", ApprovalStatus.PENDING, ApprovalStatus.EXPIRED, T0, Optional.empty()))
                .isFalse();
        assertThat(repo.findLatestForSystem(I1)).contains(a);
    }

    // Covers: 9.2, I10 (a system move, with no decider, keeps the decision already recorded)
    @Test
    void transition_keepsDecision_whenNoDeciderIsGiven() {
        put(approval("a1", I1, ALICE));
        repo.transition("a1", ApprovalStatus.PENDING, ApprovalStatus.APPROVED, T0.plusSeconds(5), Optional.of(ALICE));

        repo.transition("a1", ApprovalStatus.APPROVED, ApprovalStatus.CONSUMED, T0.plusSeconds(9), Optional.empty());

        ApprovalRecord stored = repo.findLatestForSystem(I1).orElseThrow();
        assertThat(stored.status()).isEqualTo(ApprovalStatus.CONSUMED);
        assertThat(stored.decidedAt()).contains(T0.plusSeconds(5));
        assertThat(stored.decidedBy()).contains(ALICE);
    }

    // Covers: 9.2 (single use: of many concurrent decisions on one pending approval, exactly one wins)
    @Test
    void transition_succeedsForExactlyOneCaller_whenRacing() throws Exception {
        put(approval("a1", I1, ALICE));
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<?>> done = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                done.add(pool.submit(() -> {
                    start.await();
                    if (repo.transition("a1", ApprovalStatus.PENDING, ApprovalStatus.APPROVED, T0, Optional.of(ALICE))) {
                        wins.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(wins.get()).isEqualTo(1);
    }
}
