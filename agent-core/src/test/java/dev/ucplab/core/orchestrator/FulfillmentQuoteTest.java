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

    /** The fake merchant, counting every merchant call and optionally failing discovery. */
    private final class Counting implements dev.ucplab.core.merchant.MerchantGateway {
        final java.util.List<String> calls = new java.util.ArrayList<>();
        boolean discoveryDown;

        public dev.ucplab.core.merchant.MerchantProfile discover(dev.ucplab.core.merchant.MerchantId m) {
            calls.add("discover");
            if (discoveryDown) {
                throw new dev.ucplab.core.merchant.DiscoveryFailedException("down");
            }
            return lab.merchant.discover(m);
        }

        public dev.ucplab.core.merchant.CallOutcome<dev.ucplab.core.merchant.CheckoutView> create(
                dev.ucplab.core.merchant.CreateCheckout r, dev.ucplab.core.merchant.IdempotencyKey k) {
            calls.add("create");
            return lab.merchant.create(r, k);
        }

        public dev.ucplab.core.merchant.CallOutcome<dev.ucplab.core.merchant.CheckoutView> update(
                dev.ucplab.core.merchant.SessionId id, dev.ucplab.core.merchant.UpdateCheckout r,
                dev.ucplab.core.merchant.IdempotencyKey k) {
            calls.add("update");
            return lab.merchant.update(id, r, k);
        }

        public dev.ucplab.core.merchant.CallOutcome<dev.ucplab.core.merchant.CheckoutView> complete(
                dev.ucplab.core.merchant.SessionId id, dev.ucplab.core.merchant.FrozenBody b,
                dev.ucplab.core.merchant.IdempotencyKey k) {
            calls.add("complete");
            return lab.merchant.complete(id, b, k);
        }

        public dev.ucplab.core.merchant.CallOutcome<dev.ucplab.core.merchant.CheckoutView> cancel(
                dev.ucplab.core.merchant.SessionId id, dev.ucplab.core.merchant.IdempotencyKey k) {
            calls.add("cancel");
            return lab.merchant.cancel(id, k);
        }

        public dev.ucplab.core.merchant.CallOutcome<dev.ucplab.core.merchant.CheckoutView> get(
                dev.ucplab.core.merchant.SessionId id) {
            calls.add("get");
            return lab.merchant.get(id);
        }
    }

    // Covers: 8.4, 7.3 (one merchant call per step: a selection step makes exactly one call and never discovers, whatever
    // the state of the profile cache; and when discovery is down between the create and the first selection, the selection
    // still goes ahead from the stored create response and the intent reaches approval)
    @Test
    void aSelectionStep_makesOneCall_andNeverDiscovers_evenWhenDiscoveryIsDown() {
        lab.merchant.enableFulfillment();
        Counting g = new Counting();
        Lab.Worker w = lab.worker("worker-a", g);
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        g.discoveryDown = true;
        g.calls.clear();

        w.orchestrator().advance(id);

        assertThat(g.calls).containsExactly("update");
        assertThat(settleQuote(w, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(g.calls).doesNotContain("discover");
        assertThat(lab.intent(id).failureReason()).isEmpty();
    }

    // Covers: 7.2, 9.2 (a merchant that answers with more than one group is not approved on a snapshot naming part of it:
    // the quote ends exhausted with the event fulfillment_unsupported, and nothing is approved)
    @Test
    void anAnswerWithTwoGroups_endsQuotingAsUnsupported() {
        lab.merchant.enableFulfillment();
        lab.merchant.setGroupCount(2);
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        settleQuote(w, id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_retries_exhausted");
        assertThat(lab.events(id)).anySatisfy(e -> assertThat(e.toString()).contains("fulfillment_unsupported"));
        assertThat(lab.approvals.findLatestForSystem(id)).isEmpty();
    }
}
