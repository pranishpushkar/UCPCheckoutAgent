package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InMemoryIntentRepositoryTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final UserId ALICE = new UserId("alice");
    private static final UserId BOB = new UserId("bob");
    private static final MerchantId SHOP = new MerchantId("shop-1");
    private static final String W1 = "worker-1";
    private static final String W2 = "worker-2";

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryIntentRepository repo = new InMemoryIntentRepository(clock);

    private PurchaseIntent proposed(String id, UserId owner, String dedupeKey) {
        return PurchaseIntent.proposed(new IntentId(id), owner, SHOP, dedupeKey, clock.instant());
    }

    /** Inserts an intent in QUOTING and claims it for {@code worker}; returns the claimed copy (version 0). */
    private PurchaseIntent insertAndClaim(String id, UserId owner, String dedupeKey, String worker) {
        repo.insert(proposed(id, owner, dedupeKey).withState(IntentState.QUOTING, clock.instant()));
        List<PurchaseIntent> claimed = repo.claimRecoverable(worker, LEASE, 10);
        return claimed.stream().filter(i -> i.id().value().equals(id)).findFirst().orElseThrow();
    }

    private MerchantAttempt attempt(String attemptId, PurchaseIntent intent) {
        return MerchantAttempt.sent(
                new AttemptId(attemptId), intent.id(), AttemptOp.CREATE, 0,
                Optional.of(IdempotencyKey.derive(intent.id(), MutatingOp.CREATE, 0)), Optional.empty(),
                clock.instant());
    }

    // Covers: T2, T6 (an owner-scoped read returns the owner's intent)
    @Test
    void find_returnsIntent_forItsOwner() {
        PurchaseIntent p = proposed("i1", ALICE, "d1");
        repo.insert(p);

        assertThat(repo.find(ALICE, p.id())).contains(p);
    }

    // Covers: T2, T6 (another user gets empty, the same as for an intent that does not exist)
    @Test
    void find_returnsEmpty_forAnotherUserAndForUnknownId() {
        PurchaseIntent p = proposed("i1", ALICE, "d1");
        repo.insert(p);

        assertThat(repo.find(BOB, p.id())).isEmpty();
        assertThat(repo.find(ALICE, new IntentId("missing"))).isEmpty();
        assertThat(repo.find(BOB, new IntentId("missing"))).isEqualTo(repo.find(BOB, p.id()));
    }

    // Covers: T2 (system reads are not owner-scoped, for RecoveryWorker and the eval harness)
    @Test
    void findForSystem_returnsIntent_whoeverOwnsIt() {
        PurchaseIntent p = proposed("i1", ALICE, "d1");
        repo.insert(p);

        assertThat(repo.findForSystem(p.id())).contains(p);
        assertThat(repo.findForSystem(new IntentId("missing"))).isEmpty();
    }

    // Covers: 7.7 (an active intent blocks a second insert with the same dedupe key)
    @Test
    void insert_throws_whenActiveIntentHasSameDedupeKey() {
        repo.insert(proposed("i1", ALICE, "d1"));

        assertThatThrownBy(() -> repo.insert(proposed("i2", ALICE, "d1")))
                .isInstanceOf(DuplicateIntentException.class);
        assertThat(repo.findActiveByDedupeKey("d1")).map(PurchaseIntent::id).contains(new IntentId("i1"));
    }

    // Covers: 7.7 (a terminal intent no longer holds its dedupe key)
    @Test
    void insert_succeeds_whenEarlierIntentWithSameKeyIsTerminal() {
        repo.insert(proposed("i1", ALICE, "d1").withState(IntentState.REJECTED, clock.instant()));

        repo.insert(proposed("i2", ALICE, "d1"));

        assertThat(repo.findActiveByDedupeKey("d1")).map(PurchaseIntent::id).contains(new IntentId("i2"));
    }

    // Covers: 7.7 (a repeated intent id is refused)
    @Test
    void insert_throws_whenIdRepeats() {
        repo.insert(proposed("i1", ALICE, "d1"));

        assertThatThrownBy(() -> repo.insert(proposed("i1", BOB, "d2")))
                .isInstanceOf(DuplicateIntentException.class);
    }

    // Covers: 7.8 (a successful update writes the fields and moves the version from v to v+1)
    @Test
    void update_writesFieldsAndBumpsVersion_whenVersionAndLeaseMatch() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        PurchaseIntent next = claimed.withState(IntentState.AWAITING_APPROVAL, clock.instant())
                .withSessionId(new SessionId("s1"));

        repo.update(next, 0, W1);

        PurchaseIntent stored = repo.findForSystem(claimed.id()).orElseThrow();
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(stored.sessionId()).contains(new SessionId("s1"));
    }

    // Covers: 7.8 (a write with an old version fails and changes nothing)
    @Test
    void update_throwsStaleVersion_whenVersionIsOld() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        repo.update(claimed.withState(IntentState.COMPLETING, clock.instant()), 0, W1);

        assertThatThrownBy(() -> repo.update(claimed.withState(IntentState.FAILED_NOT_CHARGED, clock.instant()), 0, W1))
                .isInstanceOf(StaleVersionException.class);

        PurchaseIntent stored = repo.findForSystem(claimed.id()).orElseThrow();
        assertThat(stored.version()).isEqualTo(1);
        assertThat(stored.state()).isEqualTo(IntentState.COMPLETING);
    }

    // Covers: 7.6 (a worker that does not hold the lease cannot write, even with the right version)
    @Test
    void update_throwsLeaseLost_whenAnotherWorkerHoldsTheLease() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);

        assertThatThrownBy(() -> repo.update(claimed.withState(IntentState.COMPLETING, clock.instant()), 0, W2))
                .isInstanceOf(LeaseLostException.class);

        assertThat(repo.findForSystem(claimed.id()).orElseThrow().version()).isZero();
    }

    // Covers: 7.6 (an intent that was never claimed has no lease owner, so no fenced write is possible)
    @Test
    void update_throwsLeaseLost_whenIntentWasNeverClaimed() {
        PurchaseIntent p = proposed("i1", ALICE, "d1");
        repo.insert(p);

        assertThatThrownBy(() -> repo.update(p.withState(IntentState.QUOTING, clock.instant()), 0, W1))
                .isInstanceOf(LeaseLostException.class);
    }

    // Covers: 7.6 (a paused worker whose lease expired and was reclaimed fails its next write)
    @Test
    void update_throwsLeaseLost_afterLeaseWasReclaimedByAnotherWorker() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        clock.advance(LEASE.plusSeconds(1));
        assertThat(repo.claimRecoverable(W2, LEASE, 10)).extracting(PurchaseIntent::id).containsExactly(claimed.id());

        assertThatThrownBy(() -> repo.update(claimed.withState(IntentState.COMPLETING, clock.instant()), 0, W1))
                .isInstanceOf(LeaseLostException.class);
        repo.update(claimed.withState(IntentState.COMPLETING, clock.instant()), 0, W2);
    }

    // Covers: 7.6 (the lease is checked before the version, so a fenced-out worker sees LeaseLost)
    @Test
    void update_throwsLeaseLost_whenBothLeaseAndVersionAreWrong() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);

        assertThatThrownBy(() -> repo.update(claimed, 7, W2)).isInstanceOf(LeaseLostException.class);
    }

    // Covers: T2, 7.6 (update never changes owner, merchant, dedupe key or lease, whatever the caller's copy says)
    @Test
    void update_keepsOwnerAndLease_whateverTheCallerPasses() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        PurchaseIntent forged = new PurchaseIntent(
                claimed.id(), BOB, new MerchantId("other"), "forged", IntentState.COMPLETING, 99,
                Optional.empty(), Optional.empty(), 0, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(W2), Optional.of(T0.plusSeconds(9999)), T0.minusSeconds(5), clock.instant());

        repo.update(forged, 0, W1);

        PurchaseIntent stored = repo.findForSystem(claimed.id()).orElseThrow();
        assertThat(stored.owner()).isEqualTo(ALICE);
        assertThat(stored.merchantId()).isEqualTo(SHOP);
        assertThat(stored.dedupeKey()).isEqualTo("d1");
        assertThat(stored.createdAt()).isEqualTo(claimed.createdAt());
        assertThat(stored.leaseOwner()).contains(W1);
        assertThat(stored.leaseUntil()).isEqualTo(claimed.leaseUntil());
        assertThat(stored.version()).isEqualTo(1);
        assertThat(repo.find(BOB, claimed.id())).isEmpty();
    }

    // Covers: 7.8 (an update for an unknown intent is a programming error, not a stale write)
    @Test
    void update_throwsNotFound_forUnknownIntent() {
        assertThatThrownBy(() -> repo.update(proposed("ghost", ALICE, "d1"), 0, W1))
                .isInstanceOf(IntentNotFoundException.class);
    }

    // Covers: 7.8 (of two writers holding version 0, exactly one wins; the other gets StaleVersionException)
    @Test
    void update_letsExactlyOneWriterWin_whenTwoRaceOnSameVersion() throws Exception {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stale = new AtomicInteger();
        AtomicInteger won = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int n = 0; n < writers; n++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        repo.update(claimed.withState(IntentState.COMPLETING, clock.instant()), 0, W1);
                        won.incrementAndGet();
                    } catch (StaleVersionException e) {
                        stale.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        }

        assertThat(won.get()).isEqualTo(1);
        assertThat(stale.get()).isEqualTo(writers - 1);
        assertThat(repo.findForSystem(claimed.id()).orElseThrow().version()).isEqualTo(1);
    }

    // Covers: 7.3 step 3 (the attempt row and the intent update commit together)
    @Test
    void recordAttempt_storesAttemptAndUpdatesIntent_together() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        MerchantAttempt a = attempt("a1", claimed);

        repo.recordAttempt(a, claimed.withState(IntentState.QUOTING, clock.instant()), 0, W1);

        assertThat(repo.attempts(claimed.id())).containsExactly(a);
        assertThat(repo.findForSystem(claimed.id()).orElseThrow().version()).isEqualTo(1);
    }

    // Covers: 7.3 step 3, 7.8 (a stale version leaves no attempt row behind)
    @Test
    void recordAttempt_storesNothing_whenVersionIsStale() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);

        assertThatThrownBy(() -> repo.recordAttempt(attempt("a1", claimed), claimed, 3, W1))
                .isInstanceOf(StaleVersionException.class);

        assertThat(repo.attempts(claimed.id())).isEmpty();
        assertThat(repo.findForSystem(claimed.id()).orElseThrow().version()).isZero();
    }

    // Covers: 7.3 step 3, 7.6 (a lost lease leaves no attempt row behind)
    @Test
    void recordAttempt_storesNothing_whenLeaseIsLost() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);

        assertThatThrownBy(() -> repo.recordAttempt(attempt("a1", claimed), claimed, 0, W2))
                .isInstanceOf(LeaseLostException.class);

        assertThat(repo.attempts(claimed.id())).isEmpty();
        assertThat(repo.findForSystem(claimed.id()).orElseThrow().version()).isZero();
    }

    // Covers: 7.3 step 3 (an attempt for a different intent than the one being updated is refused whole)
    @Test
    void recordAttempt_throws_whenAttemptBelongsToAnotherIntent() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        PurchaseIntent other = proposed("i2", ALICE, "d2");

        assertThatThrownBy(() -> repo.recordAttempt(attempt("a1", other), claimed, 0, W1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repo.findForSystem(claimed.id()).orElseThrow().version()).isZero();
    }

    // Covers: 7.3 step 3 (a repeated attempt id is refused and the intent is not updated)
    @Test
    void recordAttempt_throwsAndKeepsVersion_whenAttemptIdRepeats() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        repo.recordAttempt(attempt("a1", claimed), claimed, 0, W1);

        assertThatThrownBy(() -> repo.recordAttempt(attempt("a1", claimed), claimed, 1, W1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(repo.findForSystem(claimed.id()).orElseThrow().version()).isEqualTo(1);
        assertThat(repo.attempts(claimed.id())).hasSize(1);
    }

    // Covers: 7.3 step 5, I7 (resolving an attempt records the outcome and keeps the frozen body and key)
    @Test
    void resolveAttempt_recordsOutcome_andKeepsFrozenBodyAndKey() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        FrozenBody body = new FrozenBody("{\"payment\":\"x\"}");
        IdempotencyKey key = IdempotencyKey.derive(claimed.id(), MutatingOp.COMPLETE, 0);
        MerchantAttempt sent = MerchantAttempt.sent(
                new AttemptId("a1"), claimed.id(), AttemptOp.COMPLETE, 0, Optional.of(key), Optional.of(body),
                clock.instant());
        repo.recordAttempt(sent, claimed.withState(IntentState.COMPLETING, clock.instant()), 0, W1);
        clock.advance(Duration.ofSeconds(2));

        repo.resolveAttempt(new AttemptId("a1"), AttemptStatus.RESPONDED, Optional.of(200), Optional.of("{}"));

        MerchantAttempt resolved = repo.attempts(claimed.id()).get(0);
        assertThat(resolved.status()).isEqualTo(AttemptStatus.RESPONDED);
        assertThat(resolved.httpStatus()).contains(200);
        assertThat(resolved.responseJson()).contains("{}");
        assertThat(resolved.respondedAt()).contains(clock.instant());
        assertThat(resolved.requestBody()).contains(body);
        assertThat(resolved.idempotencyKey()).contains(key);
        assertThat(resolved.sentAt()).isEqualTo(T0);
    }

    // Covers: 7.3 (resolving an unknown attempt is a programming error)
    @Test
    void resolveAttempt_throwsNotFound_forUnknownAttempt() {
        assertThatThrownBy(() -> repo.resolveAttempt(
                        new AttemptId("nope"), AttemptStatus.UNCERTAIN, Optional.empty(), Optional.empty()))
                .isInstanceOf(IntentNotFoundException.class);
    }

    // Covers: 7.6 (renewal extends the lease for its owner only)
    @Test
    void renewLease_extendsLease_forOwnerOnly() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        clock.advance(Duration.ofSeconds(10));

        assertThat(repo.renewLease(claimed.id(), W2, LEASE)).isFalse();
        assertThat(repo.renewLease(claimed.id(), W1, LEASE)).isTrue();

        PurchaseIntent stored = repo.findForSystem(claimed.id()).orElseThrow();
        assertThat(stored.leaseUntil()).contains(clock.instant().plus(LEASE));
        assertThat(stored.leaseOwner()).contains(W1);
    }

    // Covers: 7.6 (false means the lease is lost: unknown intent, never claimed, or taken over)
    @Test
    void renewLease_returnsFalse_whenLeaseIsLost() {
        assertThat(repo.renewLease(new IntentId("missing"), W1, LEASE)).isFalse();

        repo.insert(proposed("i0", ALICE, "d0"));
        assertThat(repo.renewLease(new IntentId("i0"), W1, LEASE)).isFalse();

        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        clock.advance(LEASE.plusSeconds(1));
        repo.claimRecoverable(W2, LEASE, 10);
        assertThat(repo.renewLease(claimed.id(), W1, LEASE)).isFalse();
    }

    // Covers: 7.6 (only QUOTING, COMPLETING and RECONCILING with no live lease are claimable)
    @Test
    void claimRecoverable_claimsOnlyRecoverableStatesWithoutLiveLease() {
        for (IntentState s : IntentState.values()) {
            repo.insert(proposed("i-" + s, ALICE, "d-" + s).withState(s, clock.instant()));
        }

        List<PurchaseIntent> claimed = repo.claimRecoverable(W1, LEASE, 100);

        assertThat(claimed).extracting(PurchaseIntent::state)
                .containsExactlyInAnyOrder(IntentState.QUOTING, IntentState.COMPLETING, IntentState.RECONCILING);
        assertThat(claimed).allSatisfy(i -> {
            assertThat(i.leaseOwner()).contains(W1);
            assertThat(i.leaseUntil()).contains(clock.instant().plus(LEASE));
        });
        assertThat(repo.claimRecoverable(W2, LEASE, 100)).isEmpty();
    }

    // Covers: 7.6 (an expired lease can be claimed, a live one cannot)
    @Test
    void claimRecoverable_reclaimsOnlyAfterLeaseExpires() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);
        clock.advance(LEASE.minusSeconds(1));
        assertThat(repo.claimRecoverable(W2, LEASE, 10)).isEmpty();

        clock.advance(Duration.ofSeconds(2));
        List<PurchaseIntent> second = repo.claimRecoverable(W2, LEASE, 10);

        assertThat(second).extracting(PurchaseIntent::id).containsExactly(claimed.id());
        assertThat(second.get(0).leaseOwner()).contains(W2);
    }

    // Covers: 7.6 (the limit caps a claim, oldest first)
    @Test
    void claimRecoverable_respectsLimit_oldestFirst() {
        repo.insert(proposed("i1", ALICE, "d1").withState(IntentState.QUOTING, clock.instant()));
        clock.advance(Duration.ofSeconds(1));
        repo.insert(proposed("i2", ALICE, "d2").withState(IntentState.QUOTING, clock.instant()));

        List<PurchaseIntent> first = repo.claimRecoverable(W1, LEASE, 1);

        assertThat(first).extracting(i -> i.id().value()).containsExactly("i1");
        assertThatThrownBy(() -> repo.claimRecoverable(W1, LEASE, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    // Covers: 7.6 (claiming sets the lease only: the version is unchanged, so the claimant writes at version 0)
    @Test
    void claimRecoverable_keepsVersion() {
        PurchaseIntent claimed = insertAndClaim("i1", ALICE, "d1", W1);

        assertThat(claimed.version()).isZero();
        assertThat(repo.findForSystem(claimed.id()).orElseThrow().version()).isZero();
    }

    // Covers: 7.3 (events keep append order; an event for an unknown intent is refused)
    @Test
    void appendEvent_keepsOrder_andRejectsUnknownIntent() {
        PurchaseIntent p = proposed("i1", ALICE, "d1");
        repo.insert(p);
        IntentEvent first = new IntentEvent("policy_ok", "{}", T0);
        IntentEvent second = new IntentEvent("quote_ready", "{}", T0.plusSeconds(1));

        repo.appendEvent(p.id(), first);
        repo.appendEvent(p.id(), second);

        assertThat(repo.events(p.id())).containsExactly(first, second);
        assertThatThrownBy(() -> repo.appendEvent(new IntentId("ghost"), first))
                .isInstanceOf(IntentNotFoundException.class);
    }
}
