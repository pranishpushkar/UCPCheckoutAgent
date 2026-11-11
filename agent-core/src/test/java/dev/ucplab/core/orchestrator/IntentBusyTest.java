package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 23.5 intent_busy, as core reports it: {@code ApprovalDecision.Busy(retryAfter)} (Q5). */
class IntentBusyTest {

    private final Lab lab = new Lab();
    private final Lab.Worker holder = lab.worker("worker-a");
    private final IntentId id = lab.propose(holder, TWO_SHIRTS).intentId();
    private final PendingApproval pending = lab.pending(holder, id);
    private final Lab.Worker other = lab.worker("worker-b");

    // Covers: 23.5, T3 (another worker holds a live lease: Busy with the remaining lease; nothing is decided)
    @Test
    void decide_returnsBusyWithRemainingLease_whenAnotherWorkerHoldsALiveLease() {
        lab.clock.advance(Duration.ofSeconds(10));
        PurchaseIntent before = lab.intent(id);

        ApprovalDecision d = other.approvalService().decide(lab.alice(), id, pending.token(), true);

        assertThat(d).isEqualTo(new ApprovalDecision.Busy(lab.config.lease().minusSeconds(10)));
        assertThat(lab.intent(id)).isEqualTo(before);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
    }

    // Covers: 23.5 (the value follows the clock: it shrinks as the lease runs down and the decision succeeds once it ends)
    @Test
    void decide_decides_whenTheOtherLeaseHasEnded() {
        lab.clock.advance(lab.config.lease().minusSeconds(1));
        assertThat(other.approvalService().decide(lab.alice(), id, pending.token(), true))
                .isEqualTo(new ApprovalDecision.Busy(Duration.ofSeconds(1)));

        lab.clock.advance(Duration.ofSeconds(2));

        assertThat(other.approvalService().decide(lab.alice(), id, pending.token(), true))
                .isInstanceOf(ApprovalDecision.Approved.class);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
    }
}
