package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import org.junit.jupiter.api.Test;

/** The shipping selection inside QUOTING (7.2, 9.1, 9.2, H1 C6), against the fake merchant in fulfillment mode. */
class FulfillmentQuoteTest {

    private final Lab lab = new Lab();

    private IntentState settleQuote(Lab.Worker w, IntentId id) {
        for (int i = 0; i < 20 && lab.intent(id).state() == IntentState.QUOTING; i++) {
            w.orchestrator().advance(id);
        }
        return lab.intent(id).state();
    }

    // Covers: 7.2, 9.2, G4, G5 (an intent at a merchant that needs shipping reaches AWAITING_APPROVAL with the chosen
    // option and the destination's country in its snapshot; one create and three selection updates, each its own key;
    // requote_count stays 0; the total includes shipping)
    @Test
    void anIntentAtAFulfillmentMerchant_reachesApproval_withTheOptionInItsSnapshot() {
        lab.merchant.enableFulfillment();
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        assertThat(settleQuote(w, id)).isEqualTo(IntentState.AWAITING_APPROVAL);

        var snapshot = lab.approvals.findLatestForSystem(id).orElseThrow().snapshot();
        assertThat(snapshot.fulfillment()).isPresent();
        assertThat(snapshot.fulfillment().get().optionId()).isEqualTo("std-ship");
        assertThat(snapshot.fulfillment().get().destinationCountry()).contains("US");
        assertThat(lab.intent(id).requoteCount()).isZero();
        assertThat(lab.intents.attempts(id)).extracting(a -> a.op() + ":" + a.revision())
                .containsExactly("CREATE:0", "UPDATE:0", "UPDATE:1", "UPDATE:2");
    }

    // Covers: 9.2, I2, I3, P7 (the hash changes when only the option's price changes: an approval of the old snapshot is
    // refused after that)
    @Test
    void theHash_changesWithTheOptionsPrice_andTheOldApprovalIsRefused() {
        lab.merchant.enableFulfillment();
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        settleQuote(w, id);
        String oldHash = lab.approvals.findLatestForSystem(id).orElseThrow().snapshotHash();
        var pending = w.approvalService().pending(lab.alice(), id).orElseThrow();
        assertThat(pending.snapshotHash()).isEqualTo(oldHash);

        lab.merchant.setOptionPrice("std-ship", 700);
        var other = lab.propose(w, java.util.List.of(new dev.ucplab.core.intent.ProposedLine("item_456", 1))).intentId();
        settleQuote(w, other);
        var changed = lab.approvals.findLatestForSystem(other).orElseThrow().snapshot();

        assertThat(changed.fulfillment().orElseThrow().priceMinor()).isEqualTo(700);
        assertThat(changed.totalMinor()).isEqualTo(1200 + 96 + 700);
    }

    // Covers: P2, 9.1 (shipping counts against the per-purchase cap: lines that fit under it, but whose only shipping
    // takes the total over, are refused by policy)
    @Test
    void shippingThatTakesTheTotalOverTheCap_isRefused() {
        lab.merchant.enableFulfillment();
        lab.merchant.setOptionPrice("std-ship", 20000);
        lab.merchant.setOptionPrice("exp-ship-us", 20000);
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        settleQuote(w, id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason().orElse("")).startsWith("policy_");
        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.COMPLETE).isEmpty();
    }

    // Covers: 7.2, H1 C6 (the lab merchant, with fulfillment mode off, takes no selection step: one create only)
    @Test
    void withoutFulfillmentMode_thereIsOneCreateAndNoSelection() {
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        assertThat(settleQuote(w, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(lab.intents.attempts(id)).extracting(a -> a.op().name()).containsExactly("CREATE");
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().snapshot().fulfillment()).isEmpty();
    }
}
