package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.APPROVED;
import static dev.ucplab.core.intent.IntentState.AWAITING_APPROVAL;
import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.core.recovery.RecoveryWorker;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@code RecoveryWorker.driveNow} (7.6, m6-plan section 12): the approval path drives an APPROVED intent at once instead
 * of waiting for the next pass, under the same claim and lease rules, so a concurrent pass never makes a second complete
 * (I1, I7). Concurrency is arranged with latches, not sleeps.
 */
class DriveNowTest {

    private static final String WORKER = "worker-service";

    private final Lab lab = new Lab();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    /** Completes that reached the gateway. */
    private final AtomicInteger completes = new AtomicInteger();
    /** Counted down when a complete is in flight. */
    private final CountDownLatch inComplete = new CountDownLatch(1);
    /** Counted down to let the complete in flight reach the merchant. */
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stopPool() throws InterruptedException {
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** The lab merchant, with every complete counted and held at {@link #release}. */
    private MerchantGateway holdingCompletes() {
        return (MerchantGateway) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {MerchantGateway.class}, (proxy, method, args) -> {
                    if (method.getName().equals("complete")) {
                        completes.incrementAndGet();
                        inComplete.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    }
                    try {
                        return method.invoke(lab.merchant, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private RecoveryWorker worker(String id, Lab.Worker w, java.util.concurrent.Executor executor) {
        return new RecoveryWorker(lab.intents, lab.scan, w.orchestrator(), lab.clock, lab.clock::advance, executor,
                RecoveryConfig.defaults(), id);
    }

    /** An intent Alice approved through {@code w}: APPROVED, with the lease the decision took still held by w. */
    private IntentId approved(Lab.Worker w) {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        assertThat(lab.intent(id).state()).isEqualTo(APPROVED);
        return id;
    }

    // Covers: 7.6, I1 (driveNow on an APPROVED intent sends exactly one complete and reaches COMPLETED with one order)
    @Test
    void driveNow_approvedIntent_sendsExactlyOneComplete() {
        release.countDown();
        Lab.Worker w = lab.worker(WORKER, holdingCompletes());
        IntentId id = approved(w);

        assertThat(worker(WORKER, w, Runnable::run).driveNow(id)).isTrue();

        assertThat(lab.intent(id).state()).isEqualTo(COMPLETED);
        assertThat(completes).hasValue(1);
        assertThat(lab.merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(1);
        assertThat(lab.merchant.orders()).hasSize(1);
    }

    // Covers: 7.6, I1, I7 (driveNow, then a recovery pass while its complete is in flight: the pass starts no second
    // driver, and there is exactly one complete and one order)
    @Test
    void driveNow_thenAConcurrentPass_isStillExactlyOneComplete() throws Exception {
        Lab.Worker w = lab.worker(WORKER, holdingCompletes());
        IntentId id = approved(w);
        RecoveryWorker recovery = worker(WORKER, w, pool);

        assertThat(recovery.driveNow(id)).isTrue();
        assertThat(inComplete.await(10, TimeUnit.SECONDS)).isTrue();
        List<IntentId> startedByPass = recovery.runOnce();
        assertThat(recovery.driveNow(id)).as("a second driveNow while one is in flight").isFalse();
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(startedByPass).as("the pass saw the intent in flight").isEmpty();
        assertThat(completes).hasValue(1);
        assertThat(lab.merchant.orders()).hasSize(1);
        assertThat(lab.intent(id).state()).isEqualTo(COMPLETED);
    }

    // Covers: 7.6, I1, I7 (a recovery pass first, with its complete in flight: driveNow starts nothing, and there is
    // exactly one complete and one order)
    @Test
    void passThenDriveNow_isStillExactlyOneComplete() throws Exception {
        Lab.Worker w = lab.worker(WORKER, holdingCompletes());
        IntentId id = approved(w);
        RecoveryWorker recovery = worker(WORKER, w, pool);

        assertThat(recovery.runOnce()).containsExactly(id);
        assertThat(inComplete.await(10, TimeUnit.SECONDS)).isTrue();
        boolean started = recovery.driveNow(id);
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(started).isFalse();
        assertThat(completes).hasValue(1);
        assertThat(lab.merchant.orders()).hasSize(1);
    }

    // Covers: 7.6, I1 (after a driven intent completes, a later pass or driveNow sends nothing more)
    @Test
    void driveNow_afterCompletion_sendsNothingMore() {
        release.countDown();
        Lab.Worker w = lab.worker(WORKER, holdingCompletes());
        IntentId id = approved(w);
        RecoveryWorker recovery = worker(WORKER, w, Runnable::run);
        recovery.driveNow(id);

        assertThat(recovery.driveNow(id)).isFalse();
        recovery.runOnce();

        assertThat(completes).hasValue(1);
        assertThat(lab.merchant.orders()).hasSize(1);
    }

    // Covers: 7.6 (driveNow on an intent another worker holds does nothing and does not throw; the lease is untouched)
    @Test
    void driveNow_intentHeldByAnotherWorker_doesNothing() {
        release.countDown();
        Lab.Worker other = lab.worker("worker-other", holdingCompletes());
        IntentId id = approved(other);
        Lab.Worker mine = lab.worker(WORKER, holdingCompletes());

        boolean started = worker(WORKER, mine, Runnable::run).driveNow(id);

        assertThat(started).isFalse();
        assertThat(completes).hasValue(0);
        assertThat(lab.intent(id).state()).isEqualTo(APPROVED);
        assertThat(lab.intent(id).leaseOwner()).contains("worker-other");
    }

    // Covers: 7.6 (driveNow on an intent no worker drives, here AWAITING_APPROVAL, takes no lease and sends nothing;
    // so does an unknown id)
    @Test
    void driveNow_stateNoWorkerDrives_doesNothing() {
        release.countDown();
        Lab.Worker w = lab.worker(WORKER, holdingCompletes());
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.clock.advance(lab.config.lease().plus(Duration.ofSeconds(1)));
        assertThat(lab.intent(id).state()).isEqualTo(AWAITING_APPROVAL);
        RecoveryWorker recovery = worker(WORKER, w, Runnable::run);

        assertThat(recovery.driveNow(id)).isFalse();
        assertThat(recovery.driveNow(new IntentId("missing"))).isFalse();

        assertThat(lab.intent(id).state()).isEqualTo(AWAITING_APPROVAL);
        assertThat(lab.intent(id).leaseUntil()).hasValueSatisfying(t -> assertThat(t).isBefore(lab.clock.instant()));
        assertThat(completes).hasValue(0);
    }
}
