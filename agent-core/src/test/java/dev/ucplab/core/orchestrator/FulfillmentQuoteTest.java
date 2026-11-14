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

    private java.util.List<dev.ucplab.core.intent.MerchantAttempt> ops(IntentId id, AttemptOp op) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == op).toList();
    }

    private IntentState approveAndDrive(Lab.Worker w, IntentId id) {
        lab.decide(w, id, true);
        return lab.drive(w, id);
    }

    // Covers: S16, P8, P7, I1, I2, I3, I7 (after the three selection updates two re-quotes are still allowed and the third
    // price change ends REJECTED price_unstable; requote_count counts only the re-quotes; every update has its own key)
    @Test
    void s16_afterASelection_stillAllowsTwoReQuotes() {
        lab.merchant.enableFulfillment();
        lab.merchant.addRule(dev.ucplab.core.merchant.fake.FaultRule.nth(dev.ucplab.core.merchant.fake.MerchantCall.COMPLETE, 1,
                new dev.ucplab.core.merchant.fake.Fault.PriceChange("item_123", 2600)));
        lab.merchant.addRule(dev.ucplab.core.merchant.fake.FaultRule.nth(dev.ucplab.core.merchant.fake.MerchantCall.COMPLETE, 2,
                new dev.ucplab.core.merchant.fake.Fault.PriceChange("item_123", 2700)));
        lab.merchant.addRule(dev.ucplab.core.merchant.fake.FaultRule.nth(dev.ucplab.core.merchant.fake.MerchantCall.COMPLETE, 3,
                new dev.ucplab.core.merchant.fake.Fault.PriceChange("item_123", 2800)));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        settleQuote(w, id);
        int approvals = 0;
        IntentState state = IntentState.AWAITING_APPROVAL;
        while (state == IntentState.AWAITING_APPROVAL) {
            state = approveAndDrive(w, id);
            approvals++;
        }

        assertThat(state).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(id).failureReason()).contains("price_unstable");
        assertThat(lab.intent(id).requoteCount()).isEqualTo(2);
        assertThat(approvals).isEqualTo(3);
        assertThat(ops(id, AttemptOp.UPDATE)).hasSize(5);    // 3 selections + 2 re-quotes
        assertThat(ops(id, AttemptOp.UPDATE)).extracting(a -> a.idempotencyKey().orElseThrow()).doesNotHaveDuplicates();
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: 8.4, 7.2 (a re-quote repeats the selection: the update after a price change carries the destination and the
    // option the last response showed, and the checkout is ready again without a new selection)
    @Test
    void aReQuote_repeatsTheSelection() {
        lab.merchant.enableFulfillment();
        lab.merchant.addRule(dev.ucplab.core.merchant.fake.FaultRule.nth(dev.ucplab.core.merchant.fake.MerchantCall.COMPLETE, 1,
                new dev.ucplab.core.merchant.fake.Fault.PriceChange("item_123", 2600)));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        settleQuote(w, id);

        assertThat(approveAndDrive(w, id)).isEqualTo(IntentState.AWAITING_APPROVAL);

        var requote = ops(id, AttemptOp.UPDATE).get(3);
        assertThat(requote.requestBody().orElseThrow().json()).contains("\"selected_destination_id\":\"addr_1\"")
                .contains("\"selected_option_id\":\"std-ship\"");
        assertThat(ops(id, AttemptOp.UPDATE)).hasSize(4);
        assertThat(lab.intent(id).requoteCount()).isEqualTo(1);
    }

    // Covers: 7.2, MAX_SELECTION_STEPS (a merchant that drops the option on the re-quote: the new cycle selects it again,
    // inside the step cap, and the intent reaches a new approval)
    @Test
    void ifTheMerchantResetsTheSelection_theNewCycleSelectsAgain() {
        lab.merchant.enableFulfillment();
        lab.merchant.addRule(dev.ucplab.core.merchant.fake.FaultRule.nth(dev.ucplab.core.merchant.fake.MerchantCall.COMPLETE, 1,
                new dev.ucplab.core.merchant.fake.Fault.PriceChange("item_123", 2600)));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        settleQuote(w, id);
        lab.merchant.forgetNextOption();

        assertThat(approveAndDrive(w, id)).isEqualTo(IntentState.AWAITING_APPROVAL);

        assertThat(ops(id, AttemptOp.UPDATE)).hasSize(5);    // 3 selections, the re-quote, one more option selection
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().snapshot().fulfillment()).isPresent();
    }

    // Covers: I2, I3, P7, 9.2 (the approval's total includes shipping, and when the option's price rises after approval
    // complete is not accepted at the old total: the merchant reports a price change, the intent re-quotes, the new
    // snapshot has the new price and needs a new approval, and no order is above the approved maximum)
    @Test
    void aRiseInShipping_afterApproval_needsANewApproval() {
        lab.merchant.enableFulfillment();
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        settleQuote(w, id);
        var first = lab.approvals.findLatestForSystem(id).orElseThrow();
        lab.decide(w, id, true);
        lab.merchant.setOptionPrice("std-ship", 900);

        IntentState state = lab.drive(w, id);

        assertThat(state).isEqualTo(IntentState.AWAITING_APPROVAL);
        var second = lab.approvals.findLatestForSystem(id).orElseThrow();
        assertThat(second.snapshot().fulfillment().orElseThrow().priceMinor()).isEqualTo(900);
        assertThat(second.snapshot().totalMinor()).isEqualTo(first.snapshot().totalMinor() + 900);
        assertThat(second.snapshotHash()).isNotEqualTo(first.snapshotHash());
        assertThat(lab.merchant.orders()).isEmpty();
        assertThat(lab.intent(id).requoteCount()).isEqualTo(1);
    }

    /** The fake merchant, except that complete answers the way the sample's 4xx refusals are reported by the gateway. */
    private final class Refusing implements dev.ucplab.core.merchant.MerchantGateway {
        final int http;
        final String code;
        int completes;

        Refusing(int http, String code) {
            this.http = http;
            this.code = code;
        }

        public dev.ucplab.core.merchant.MerchantProfile discover(dev.ucplab.core.merchant.MerchantId m) {
            return lab.merchant.discover(m);
        }

        public dev.ucplab.core.merchant.CallOutcome<dev.ucplab.core.merchant.CheckoutView> create(
                dev.ucplab.core.merchant.CreateCheckout r, dev.ucplab.core.merchant.IdempotencyKey k) {
            return lab.merchant.create(r, k);
        }

        public dev.ucplab.core.merchant.CallOutcome<dev.ucplab.core.merchant.CheckoutView> update(
                dev.ucplab.core.merchant.SessionId id, dev.ucplab.core.merchant.UpdateCheckout r,
                dev.ucplab.core.merchant.IdempotencyKey k) {
            return lab.merchant.update(id, r, k);
        }

        public dev.ucplab.core.merchant.CallOutcome<dev.ucplab.core.merchant.CheckoutView> complete(
                dev.ucplab.core.merchant.SessionId id, dev.ucplab.core.merchant.FrozenBody b,
                dev.ucplab.core.merchant.IdempotencyKey k) {
            completes++;
            var message = new dev.ucplab.core.merchant.MerchantMessage("error", code, java.util.Optional.empty(), "refused",
                    java.util.Optional.empty());
            var view = new dev.ucplab.core.merchant.CheckoutView(id, dev.ucplab.core.merchant.CheckoutStatus.READY_FOR_COMPLETE,
                    "", java.util.List.of(), 0, 0, 0, java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.List.of(message));
            return new dev.ucplab.core.merchant.CallOutcome.Ok<>(http, view, java.util.List.of(message));
        }

        public dev.ucplab.core.merchant.CallOutcome<dev.ucplab.core.merchant.CheckoutView> cancel(
                dev.ucplab.core.merchant.SessionId id, dev.ucplab.core.merchant.IdempotencyKey k) {
            return lab.merchant.cancel(id, k);
        }

        public dev.ucplab.core.merchant.CallOutcome<dev.ucplab.core.merchant.CheckoutView> get(
                dev.ucplab.core.merchant.SessionId id) {
            return lab.merchant.get(id);
        }
    }

    private void refusalEndsNotCharged(int http, String code, String reason) {
        Refusing g = new Refusing(http, code);
        Lab.Worker w = lab.worker("worker-a", g);
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);

        IntentState state = lab.drive(w, id);

        assertThat(state).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains(reason);
        assertThat(g.completes).isEqualTo(1);
        assertThat(ops(id, AttemptOp.COMPLETE)).singleElement().satisfies(a -> {
            assertThat(a.httpStatus()).contains(http);
            assertThat(a.status()).isEqualTo(dev.ucplab.core.intent.AttemptStatus.RESPONDED);
        });
        assertThat(ops(id, AttemptOp.CANCEL)).as("cancel owed").isNotEmpty();
        assertThat(lab.events(id)).noneSatisfy(e -> assertThat(e.toString()).contains("RECONCILING"));
    }

    // Covers: 8.1, 7.2, 8.2 (fixture 26: 409 OUT_OF_STOCK at complete: FAILED_NOT_CHARGED out_of_stock, cancel owed, one
    // complete, the 409 on the attempt, never RECONCILING)
    @Test
    void aSampleStyle409OutOfStock_endsNotCharged() {
        refusalEndsNotCharged(409, "out_of_stock", "out_of_stock");
    }

    // Covers: 8.1, 7.2, 8.2 (fixture 31: 402 INSUFFICIENT_FUNDS at complete: payment_declined, otherwise the same)
    @Test
    void aSampleStyle402Decline_endsNotCharged() {
        refusalEndsNotCharged(402, "payment_declined", "payment_declined");
    }

    // Covers: 7.2, 9.2 (the two-groups check covers every Ok answer in QUOTING: a merchant that answers the create
    // ready_for_complete with two groups already chosen is not approved on a snapshot naming only the first)
    @Test
    void aReadyAnswerWithTwoGroups_endsQuotingAsUnsupported() {
        lab.merchant.enableFulfillment();
        lab.merchant.setGroupCount(2);
        lab.merchant.preselectShipping();
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        settleQuote(w, id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_retries_exhausted");
        assertThat(lab.events(id)).anySatisfy(e -> assertThat(e.toString()).contains("fulfillment_unsupported"));
        assertThat(lab.approvals.findLatestForSystem(id)).isEmpty();
    }
}
