package dev.ucplab.core.intent;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MutatingOp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The repository's one lock is released after every call (7.8: never hold a lock longer than the call): after each public
 * method another thread can read the intent, and gives up after two seconds instead of waiting for ever. Found by the H3
 * P6 mutation pass, where removing an {@code unlock()} made a run hang until PIT's timeout (thirteen of them) or, for
 * four methods, went unnoticed because the test that follows runs on the same thread and a lock is reentrant.
 */
class InMemoryIntentRepositoryLockTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final String W1 = "worker-1";

    private final MutableClock clock = new MutableClock(T0);
    private final InMemoryIntentRepository repo = new InMemoryIntentRepository(clock);
    private final ExecutorService other = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "other-thread");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void stop() {
        other.shutdownNow();
    }

    /** What a second thread sees within two seconds: a held lock is a timeout, which fails the test fast. */
    private <T> T elsewhere(Callable<T> call) throws Exception {
        try {
            return other.submit(call).get(2, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AssertionError("the repository's lock is still held after the call returned", e);
        }
    }

    private void unlocked(IntentId id) throws Exception {
        assertThat(elsewhere(() -> repo.findForSystem(id))).isPresent();
    }

    // Covers: 7.8. Kills InMemoryIntentRepository unlock removed in find:51, findForSystem:62, findActiveByDedupeKey:75,
    // insert:94, update:107, appendEvent:120, recordAttempt:144, resolveAttempt:163 and :192, resolveAttempts:223,
    // claim:248, claimRecoverable:281, renewLease:299, inState:311, ownedBy:324, events:334 and attempts:345 (VoidMethodCall)
    @Test
    void everyPublicMethod_releasesTheLock() throws Exception {
        UserId alice = new UserId("alice");
        PurchaseIntent quoting = PurchaseIntent.proposed(new IntentId("i1"), alice, new MerchantId("shop-1"), "d1",
                List.of(new ProposedLine("item_123", 1)), "a@example.com", T0).withState(IntentState.QUOTING, T0);
        IntentId id = quoting.id();

        repo.insert(quoting);
        unlocked(id);
        repo.find(alice, id);
        unlocked(id);
        repo.findForSystem(id);
        unlocked(id);
        repo.findActiveByDedupeKey("d1");
        unlocked(id);
        PurchaseIntent claimed = repo.claim(id, W1, LEASE).orElseThrow();
        unlocked(id);
        assertThat(repo.claimRecoverable(W1, LEASE, 5)).hasSize(1);
        unlocked(id);
        assertThat(repo.renewLease(id, W1, LEASE)).isTrue();
        unlocked(id);

        repo.update(claimed, 0, W1);
        unlocked(id);
        repo.appendEvent(id, IntentEvent.of("note", java.util.Map.of("k", "v"), T0));
        unlocked(id);
        MerchantAttempt a1 = MerchantAttempt.sent(new AttemptId("a1"), id, AttemptOp.CREATE, 0,
                Optional.of(IdempotencyKey.derive(id, MutatingOp.CREATE, 0)), Optional.empty(), T0);
        repo.recordAttempt(a1, repo.findForSystem(id).orElseThrow(), 1, W1);
        unlocked(id);
        repo.resolveAttempt(new AttemptId("a1"), AttemptStatus.RESPONDED, Optional.of(201), Optional.of("{}"));
        unlocked(id);
        repo.resolveAttempt(new AttemptId("a1"), AttemptStatus.RESPONDED, Optional.of(201), Optional.of("{}"),
                repo.findForSystem(id).orElseThrow(), 2, W1);
        unlocked(id);
        repo.resolveAttempts(id, List.of(new AttemptId("a1")), AttemptStatus.RESPONDED, Optional.of(201), Optional.of("{}"),
                3, W1);
        unlocked(id);

        assertThat(repo.attempts(id)).hasSize(1);
        unlocked(id);
        assertThat(repo.events(id)).hasSize(1);
        unlocked(id);
        assertThat(repo.ownedBy(alice)).hasSize(1);
        unlocked(id);
        assertThat(repo.inState(IntentState.QUOTING)).hasSize(1);
        unlocked(id);
        assertThat(elsewhere(() -> repo.claimRecoverable("worker-2", LEASE, 5, Set.of()))).as("held by worker-1").isEmpty();
    }
}
