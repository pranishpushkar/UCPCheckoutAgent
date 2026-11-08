package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalRecord;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.approval.PendingApproval;
import dev.ucplab.core.identity.AuthContext;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** DefaultApprovalService through the orchestrator it serves; lives here to share {@link Lab}. */
class DefaultApprovalServiceTest {

    private final Lab lab = new Lab();
    private final Lab.Worker w = lab.worker("worker-a");
    private final IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
    private final PendingApproval pending = lab.pending(w, id);

    // Covers: I10, T3, T6, S18 (another user, even holding the token, gets not found; the intent is unchanged)
    @Test
    void decide_returnsNotFound_forAnotherUserWithTheToken() {
        PurchaseIntent before = lab.intent(id);

        ApprovalDecision d = w.approvalService().decide(lab.bob(), id, pending.token(), true);

        assertThat(d).isInstanceOf(ApprovalDecision.NotFound.class);
        assertThat(lab.intent(id)).isEqualTo(before);
        assertThat(w.approvalService().pending(lab.bob(), id)).isEmpty();
    }

    // Covers: T4, I10, S19 (a sign-in older than maxApprovalAuthAge is refused with reauth_required)
    @Test
    void decide_requiresRecentSignIn() {
        AuthContext stale = new AuthContext(Lab.ALICE, "alice@example.com",
                lab.clock.instant().minus(Duration.ofMinutes(16)), "session-alice");

        assertThat(w.approvalService().decide(stale, id, pending.token(), true))
                .isInstanceOf(ApprovalDecision.ReauthRequired.class);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.AWAITING_APPROVAL);
    }

    // Covers: 9.2, I3 (a token that does not match the snapshot hash changes nothing)
    @Test
    void decide_refusesWrongToken() {
        assertThat(w.approvalService().decide(lab.alice(), id, pending.token() + "x", true))
                .isInstanceOf(ApprovalDecision.InvalidToken.class);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.AWAITING_APPROVAL);
    }

    // Covers: 9.2 "Snapshot and token details", I3 (found by the property tests: the price goes 2500 -> 2800 -> 2500
    // within one instant, so approvals 1 and 3 have byte-identical snapshots and hashes; the token carries the approval
    // id, so the consumed token of approval 1 is refused for approval 3, whose own token is accepted)
    @Test
    void decide_refusesConsumedTokenOfEarlierApproval_whenSnapshotsAreIdentical() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2800)));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 2, new Fault.PriceChange("item_123", 2500)));
        assertThat(w.approvalService().decide(lab.alice(), id, pending.token(), true))
                .isInstanceOf(ApprovalDecision.Approved.class);
        assertThat(lab.drive(w, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        assertThat(lab.drive(w, id)).isEqualTo(IntentState.AWAITING_APPROVAL);

        PendingApproval third = lab.pending(w, id);
        List<ApprovalRecord> all = lab.approvals.listForSystem(id);
        assertThat(all).hasSize(3);
        assertThat(all.get(0).status()).isEqualTo(ApprovalStatus.CONSUMED);
        assertThat(third.snapshot()).isEqualTo(pending.snapshot());
        assertThat(third.snapshotHash()).isEqualTo(pending.snapshotHash());
        assertThat(third.token()).isNotEqualTo(pending.token());

        assertThat(w.approvalService().decide(lab.alice(), id, pending.token(), true))
                .isInstanceOf(ApprovalDecision.InvalidToken.class);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(w.approvalService().decide(lab.alice(), id, third.token(), true))
                .isInstanceOf(ApprovalDecision.Approved.class);
    }

    // Covers: 9.2, I3, I10 (approval is single use and records decided_by)
    @Test
    void decide_isSingleUse_andRecordsDecidedBy() {
        assertThat(w.approvalService().decide(lab.alice(), id, pending.token(), true))
                .isInstanceOf(ApprovalDecision.Approved.class);

        assertThat(w.approvalService().decide(lab.alice(), id, pending.token(), true))
                .isInstanceOf(ApprovalDecision.NotPending.class);
        ApprovalRecord record = lab.approvals.findLatestForSystem(id).orElseThrow();
        assertThat(record.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(record.decidedBy()).contains(Lab.ALICE);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
        assertThat(lab.intent(id).approvedSnapshotHash()).contains(pending.snapshotHash());
    }

    // Covers: FR3, 9.2 (an expired approval cannot be used)
    @Test
    void decide_refusesExpiredApproval() {
        lab.clock.advance(Duration.ofMinutes(10));

        assertThat(w.approvalService().decide(lab.alice(), id, pending.token(), true))
                .isInstanceOf(ApprovalDecision.NotPending.class);
        assertThat(w.approvalService().pending(lab.alice(), id)).isEmpty();
    }

    // Covers: 9.2, 23.2 (the token is not stored, but the owner's approver channel can re-read the same token)
    @Test
    void pending_reMintsTheSameToken_andStoresOnlyItsHash() {
        PendingApproval again = lab.pending(w, id);

        assertThat(again.token()).isEqualTo(pending.token());
        ApprovalRecord record = lab.approvals.findLatestForSystem(id).orElseThrow();
        assertThat(record.tokenHash()).isNotEqualTo(pending.token());
        assertThat(record.toString()).doesNotContain(pending.token());
    }
}
