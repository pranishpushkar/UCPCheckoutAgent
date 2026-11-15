package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.AWAITING_APPROVAL;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.StepResult;
import dev.ucplab.core.merchant.MerchantGateway;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.recovery.RecoveryConfig;
import dev.ucplab.core.recovery.RecoveryWorker;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Two steps of one intent in one process. The service gives its orchestrator and its recovery worker the same worker id,
 * and a lease held by that id counts as free to it (7.6), so a recovery pass can claim an intent that {@code propose} is
 * still quoting on the request thread. The quickstart smoke on PR #17 saw this as a {@code propose_purchase} tool_error.
 * Concurrency is arranged with latches, not sleeps: the first create (propose's quote) is held until the pass is done.
 */
class ConcurrentStepTest {

    private static final String WORKER = "worker-service";

    private final Lab lab = new Lab();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    /** Creates that reached the gateway. */
    private final AtomicInteger creates = new AtomicInteger();
    /** Counted down when the first create is in flight. */
    private final CountDownLatch inCreate = new CountDownLatch(1);
    /** Counted down to let the first create reach the merchant. */
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stopPool() throws InterruptedException {
        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }

    /** The lab merchant, with every create counted and only the first one held at {@link #release}. */
    private MerchantGateway holdingTheFirstCreate() {
        return (MerchantGateway) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {MerchantGateway.class}, (proxy, method, args) -> {
                    if (method.getName().equals("create") && creates.incrementAndGet() == 1) {
                        inCreate.countDown();
                        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
                    }
                    try {
                        return method.invoke(lab.merchant, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // Covers: 6.2, 7.3, 7.6, I7 (a recovery pass under the same worker id while propose's quote is in flight: propose
    // does not throw, the pass's step of that intent is idle, and exactly one create reaches the merchant)
    @Test
    void aPassWhileProposeIsQuoting_doesNotStepTheIntentAgain() throws Exception {
        Lab.Worker w = lab.worker(WORKER, holdingTheFirstCreate());
        RecoveryWorker recovery = new RecoveryWorker(lab.intents, lab.scan, w.orchestrator(), lab.clock,
                lab.clock::advance, Runnable::run, RecoveryConfig.defaults(), WORKER);

        Future<ProposalResult> proposing = pool.submit(() -> lab.propose(w, TWO_SHIRTS));
        assertThat(inCreate.await(10, TimeUnit.SECONDS)).isTrue();
        List<IntentId> startedByPass = recovery.runOnce();
        release.countDown();
        ProposalResult proposal = proposing.get(10, TimeUnit.SECONDS);

        assertThat(proposal.state()).isEqualTo(AWAITING_APPROVAL);
        assertThat(proposal.quote()).isPresent();
        // The pass claims the intent and gives it a driver, whose step is idle: it leaves the lease with this process.
        assertThat(startedByPass).containsExactly(proposal.intentId());
        assertThat(creates).hasValue(1);
        assertThat(lab.merchant.delivered(MerchantCall.CREATE)).isEqualTo(1);
        assertThat(lab.intent(proposal.intentId()).state()).isEqualTo(AWAITING_APPROVAL);
    }

    // Covers: 7.3, 7.6, I7 (advance under the same worker id while propose's quote is in flight returns idle at once,
    // with the state unchanged, instead of quoting a second time)
    @Test
    void advanceWhileProposeIsQuoting_isIdle() throws Exception {
        Lab.Worker w = lab.worker(WORKER, holdingTheFirstCreate());

        Future<ProposalResult> proposing = pool.submit(() -> lab.propose(w, TWO_SHIRTS));
        assertThat(inCreate.await(10, TimeUnit.SECONDS)).isTrue();
        IntentId id = lab.intents.claimRecoverable(WORKER, lab.config.lease(), 10).getFirst().id();
        StepResult step = w.orchestrator().advance(id);
        release.countDown();
        ProposalResult proposal = proposing.get(10, TimeUnit.SECONDS);

        assertThat(step.idle()).isTrue();
        assertThat(proposal.intentId()).isEqualTo(id);
        assertThat(proposal.state()).isEqualTo(AWAITING_APPROVAL);
        assertThat(creates).hasValue(1);
    }
}
