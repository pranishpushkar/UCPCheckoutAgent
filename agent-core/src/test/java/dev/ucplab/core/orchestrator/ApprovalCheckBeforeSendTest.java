package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.APPROVED;
import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.intent.IntentState.ESCALATED;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** I3: the approval is checked before the write-ahead of complete, so a bad approval leaves no attempt behind. */
class ApprovalCheckBeforeSendTest {

    private final Lab lab = new Lab();
    private final Lab.Worker w = lab.worker("worker-a");

    private IntentId approved() {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        assertThat(lab.intent(id).state()).isEqualTo(APPROVED);
        return id;
    }

    private void assertEscalatedWithNothingSent(IntentId id) {
        assertThat(w.orchestrator().advance(id).after()).isEqualTo(ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("approval_invalid");
        assertThat(lab.intents.attempts(id)).noneMatch(a -> a.op() == AttemptOp.COMPLETE);
        assertThat(lab.merchant.log()).noneMatch(c -> c.call() == MerchantCall.COMPLETE);
        assertThat(lab.merchant.orders()).isEmpty();
        assertThat(lab.intents.events(id)).anyMatch(e -> e.type().equals("approval_invalid"));
    }

    // Covers: I3, I5, 7.2 (an EXPIRED approval under an APPROVED intent: no attempt, no merchant call, ESCALATED)
    @Test
    void advance_escalatesWithoutSending_whenNewestApprovalIsExpired() {
        IntentId id = approved();
        ApprovalRecord approval = lab.approvals.findLatestForSystem(id).orElseThrow();
        lab.approvals.transition(approval.id(), ApprovalStatus.APPROVED, ApprovalStatus.EXPIRED, lab.clock.instant(),
                Optional.empty());

        assertEscalatedWithNothingSent(id);
    }

    // Covers: I3, I5, 7.2 (a REJECTED approval under an APPROVED intent: same)
    @Test
    void advance_escalatesWithoutSending_whenNewestApprovalIsRejected() {
        IntentId id = approved();
        ApprovalRecord approval = lab.approvals.findLatestForSystem(id).orElseThrow();
        lab.approvals.transition(approval.id(), ApprovalStatus.APPROVED, ApprovalStatus.REJECTED, lab.clock.instant(),
                Optional.empty());

        assertEscalatedWithNothingSent(id);
    }

    // Covers: I3, I5, 7.2 (the snapshot hash of the newest approval is not the one the intent was approved with)
    @Test
    void advance_escalatesWithoutSending_whenApprovalHashDiffersFromApprovedSnapshot() {
        IntentId id = approved();
        PurchaseIntent intent = lab.intent(id);
        lab.intents.update(intent.withApprovedSnapshotHash(Optional.of("0".repeat(64))), intent.version(), "worker-a");

        assertEscalatedWithNothingSent(id);
    }

    // Covers: I3 (a valid approval is untouched by the check: PENDING, APPROVED and CONSUMED all send)
    @Test
    void advance_completes_whenApprovalIsApproved() {
        IntentId id = approved();

        assertThat(lab.drive(w, id)).isEqualTo(COMPLETED);
        assertThat(lab.merchant.orders()).hasSize(1);
    }
}
