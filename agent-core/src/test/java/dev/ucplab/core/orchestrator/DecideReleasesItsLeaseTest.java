package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.intent.StaleVersionException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * 9.2, 7.6, 23.5: {@code decide} releases any lease it took, on every path, including NotPending, and never a lease it did
 * not take. A decide that claimed the intent and then answered NotPending used to leave the claim to run out its 30 s, so
 * another worker's {@code advance} of the same intent was Busy (idle) for that long. "Another worker can take the intent"
 * is checked with a claim by a second worker at the same instant, which is what its advance does first.
 */
class DecideReleasesItsLeaseTest {

    private final Lab lab = new Lab();
    private final Lab.Worker a = lab.worker("worker-a");
    private final IntentId id = lab.propose(a, TWO_SHIRTS).intentId();
    private final Duration lease = lab.config.lease();

    /** The lease worker-a took at the propose has run out, so a decide by worker-a takes the lease afresh. */
    private PendingApproval pendingWithTheProposeLeaseGone() {
        PendingApproval p = lab.pending(a, id);
        lab.clock.advance(lease.plusSeconds(1));
        return p;
    }

    private void assertAnotherWorkerCanTakeTheIntent() {
        assertThat(lab.intents.claim(id, "worker-b", lease)).as("another worker's claim is not refused as busy").isPresent();
    }

    /** An intent store that fails the named call, to drive decide down a path that ends early. */
    private IntentRepository failing(String method, Predicate<Object[]> when, RuntimeException failure) {
        return (IntentRepository) Proxy.newProxyInstance(IntentRepository.class.getClassLoader(),
                new Class<?>[] {IntentRepository.class}, (proxy, m, args) -> {
                    if (m.getName().equals(method) && when.test(args)) {
                        throw failure;
                    }
                    try {
                        return m.invoke(lab.intents, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    // Covers: 9.2, 7.6, 23.5 (NotPending because the intent is no longer awaiting approval: the claim decide took is given
    // back)
    @Test
    void notPending_becauseTheIntentMoved_releasesTheLease() {
        PendingApproval p = pendingWithTheProposeLeaseGone();
        PurchaseIntent claimed = lab.intents.claim(id, "mover", lease).orElseThrow();
        lab.intents.update(claimed.withState(IntentState.QUOTING, lab.clock.instant()), claimed.version(), "mover");
        lab.clock.advance(lease.plusSeconds(1));

        ApprovalDecision d = a.approvalService().decide(lab.alice(), id, p.token(), true);

        assertThat(d).isInstanceOf(ApprovalDecision.NotPending.class);
        assertAnotherWorkerCanTakeTheIntent();
    }

    // Covers: 9.2, 7.6, 7.8 (NotPending because the intent write lost a race: the claim is given back)
    @Test
    void notPending_becauseTheWriteLostARace_releasesTheLease() {
        PendingApproval p = pendingWithTheProposeLeaseGone();
        Lab.Worker loser = lab.worker("worker-c", lab.merchant, lab.approvals,
                failing("update", args -> true, new StaleVersionException(id, 0, 1)));

        ApprovalDecision d = loser.approvalService().decide(lab.alice(), id, p.token(), true);

        assertThat(d).isInstanceOf(ApprovalDecision.NotPending.class);
        assertAnotherWorkerCanTakeTheIntent();
    }

    // Covers: 9.2, 7.6, 23.5 (an approval: the decision is made, and the lease decide took is given back)
    @Test
    void approved_releasesTheLease() {
        PendingApproval p = pendingWithTheProposeLeaseGone();

        ApprovalDecision d = a.approvalService().decide(lab.alice(), id, p.token(), true);

        assertThat(d).isInstanceOf(ApprovalDecision.Approved.class);
        assertAnotherWorkerCanTakeTheIntent();
    }

    // Covers: 9.2, 7.6, 7.3 (an adapter that stores a version other than the caller's arithmetic, 7.3: the release does not
    // depend on the version, so the lease is still freed at once after a skewed write)
    @Test
    void approved_underASkewedWrite_stillReleasesTheLeaseAtOnce() {
        PendingApproval p = pendingWithTheProposeLeaseGone();
        SkewingIntentRepository skewing = new SkewingIntentRepository(lab.intents);
        Lab.Worker skewed = lab.worker("worker-a", lab.merchant, lab.approvals, skewing);
        skewing.skewNextWrite();

        ApprovalDecision d = skewed.approvalService().decide(lab.alice(), id, p.token(), true);

        assertThat(d).isInstanceOf(ApprovalDecision.Approved.class);
        assertThat(lab.intents.findForSystem(id).orElseThrow().version()).as("the write was skewed").isGreaterThan(2);
        assertAnotherWorkerCanTakeTheIntent();
    }

    // Covers: 9.2, 7.6, 23.5 (a rejection: the same)
    @Test
    void rejected_releasesTheLease() {
        PendingApproval p = pendingWithTheProposeLeaseGone();

        ApprovalDecision d = a.approvalService().decide(lab.alice(), id, p.token(), false);

        assertThat(d).isInstanceOf(ApprovalDecision.Rejected.class);
        assertAnotherWorkerCanTakeTheIntent();
    }

    // Covers: 9.2, 7.6 (a failure inside decide, after the claim: the exception goes to the caller and the lease is still
    // given back)
    @Test
    void anExceptionAfterTheClaim_stillReleasesTheLease() {
        PendingApproval p = pendingWithTheProposeLeaseGone();
        Lab.Worker broken = lab.worker("worker-c", lab.merchant, lab.approvals,
                failing("appendEvent", args -> true, new IllegalStateException("event store down")));

        assertThatThrownBy(() -> broken.approvalService().decide(lab.alice(), id, p.token(), true))
                .isInstanceOf(IllegalStateException.class);

        assertAnotherWorkerCanTakeTheIntent();
    }

    // Covers: 9.2, 7.6 (never a lease the call did not take: the same worker still holds a live lease from the propose, so
    // decide only renewed it, and it is still held afterwards: another worker is refused, this one is not)
    @Test
    void aLeaseTheWorkerAlreadyHeld_isNotReleased() {
        PendingApproval p = lab.pending(a, id);

        ApprovalDecision d = a.approvalService().decide(lab.alice(), id, p.token(), true);

        assertThat(d).isInstanceOf(ApprovalDecision.Approved.class);
        assertThat(lab.intents.claim(id, "worker-b", lease)).as("worker-a's own live lease is kept").isEmpty();
        assertThat(lab.intents.claim(id, "worker-a", lease)).isPresent();
    }

    // Covers: 9.2, 7.6, 23.5 (never another worker's lease: a decide that is told Busy took nothing and leaves the holder's
    // lease alone)
    @Test
    void busy_leavesTheHoldersLeaseAlone() {
        PendingApproval p = pendingWithTheProposeLeaseGone();
        assertThat(lab.intents.claim(id, "holder", lease)).isPresent();

        ApprovalDecision d = a.approvalService().decide(lab.alice(), id, p.token(), true);

        assertThat(d).isInstanceOf(ApprovalDecision.Busy.class);
        assertThat(lab.intent(id).leaseOwner()).contains("holder");
        assertThat(lab.intents.claim(id, "worker-b", lease)).isEmpty();
    }
}
