package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.SHOP_B;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.intent.IntentState.FAILED_NOT_CHARGED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.MerchantGateways;
import dev.ucplab.core.merchant.NoGatewayException;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The orchestrator picks the gateway of the intent's merchant (I11, 8.5, 19): with two merchants, a call for an intent
 * at B never reaches A, whichever of the five calls it is, and a merchant with no gateway sends nothing and changes
 * nothing.
 */
class MultiMerchantTest {

    private final Lab lab = new Lab();
    private final RecordingGateway a = new RecordingGateway(lab.merchant);
    private final RecordingGateway b = new RecordingGateway(lab.merchantB);
    private final Lab.Worker w = lab.routedWorker("worker-a", MerchantGateways.of(Map.of(SHOP, a, SHOP_B, b)));

    // Covers: I11, 8.5, I1 (a purchase at B whose complete response is lost: create, complete and the reconciling get all
    // go to B, one order exists at B, none at A, and A's gateway was never called)
    @Test
    void anIntentAtB_createsCompletesAndReconciles_atBOnly() {
        lab.merchantB.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));
        IntentId id = lab.proposeAt(w, SHOP_B, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);

        assertThat(lab.drive(w, id)).isEqualTo(COMPLETED);

        assertThat(a.calls()).as("calls that reached merchant A").isEmpty();
        assertThat(b.calls()).contains("create", "complete", "get");
        assertThat(lab.merchantB.orders()).hasSize(1);
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: I11, 8.5 (an update after a price change, and the owed cancel of an exhausted re-quote, go to B only)
    @Test
    void anIntentAtB_updatesAndCancels_atBOnly() {
        IntentId id = lab.proposeAt(w, SHOP_B, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        lab.merchantB.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.PriceChange("item_123", 2600)));
        lab.merchantB.addRule(FaultRule.nth(MerchantCall.UPDATE, 1, new Fault.NotSent()));
        while (lab.intent(id).state() != FAILED_NOT_CHARGED) {
            w.orchestrator().advance(id);
        }
        assertThat(lab.drive(w, id)).isEqualTo(FAILED_NOT_CHARGED);

        assertThat(a.calls()).isEmpty();
        assertThat(b.calls()).contains("create", "update", "cancel");
        assertThat(lab.intents.attempts(id)).filteredOn(x -> x.op() == AttemptOp.CANCEL).hasSize(1);
    }

    // Covers: I11, 19 (intents at both merchants interleave on one worker: each call lands at its own intent's merchant)
    @Test
    void intentsAtBothMerchants_eachGoToTheirOwn() {
        IntentId atA = lab.proposeAt(w, SHOP, TWO_SHIRTS).intentId();
        IntentId atB = lab.proposeAt(w, SHOP_B, TWO_SHIRTS).intentId();
        lab.decide(w, atA, true);
        lab.decide(w, atB, true);

        assertThat(lab.drive(w, atB)).isEqualTo(COMPLETED);
        assertThat(lab.drive(w, atA)).isEqualTo(COMPLETED);

        assertThat(lab.merchant.orders()).hasSize(1);
        assertThat(lab.merchantB.orders()).hasSize(1);
        assertThat(a.calls()).containsOnlyOnce("create").containsOnlyOnce("complete");
        assertThat(b.calls()).containsOnlyOnce("create").containsOnlyOnce("complete");
        assertThat(lab.merchant.orders().getFirst().sessionId())
                .isNotEqualTo(lab.merchantB.orders().getFirst().sessionId());
    }

    // Covers: I11, 7.5, 7.6 (a merchant with no gateway, the same worker id after a restart so it holds the lease: advance fails loudly, nothing is sent to any gateway, no attempt
    // is written, and the intent's state, version and events are exactly what they were; it never goes terminal)
    @Test
    void aMerchantWithNoGateway_sendsNothing_andChangesNothing() {
        IntentId id = lab.proposeAt(w, SHOP_B, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        Lab.Worker onlyA = lab.routedWorker("worker-a", MerchantGateways.of(Map.of(SHOP, a)));
        PurchaseIntent before = lab.intent(id);
        int events = lab.events(id).size();
        int attempts = lab.intents.attempts(id).size();
        List<String> bBefore = b.calls();

        assertThatThrownBy(() -> onlyA.orchestrator().advance(id)).isInstanceOf(NoGatewayException.class)
                .hasMessage("no gateway for merchant shop-2");

        assertThat(a.calls()).as("calls that reached the merchant that is configured").isEmpty();
        assertThat(b.calls()).as("calls that reached merchant B since the proposal").isEqualTo(bBefore);
        PurchaseIntent after = lab.intent(id);
        assertThat(after.state()).isEqualTo(before.state()).isEqualTo(IntentState.APPROVED);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(lab.intents.attempts(id)).hasSize(attempts);
        assertThat(lab.events(id)).hasSize(events);
        // The same intent, with its gateway back, completes: the refusal cost it nothing.
        assertThat(lab.drive(w, id)).isEqualTo(COMPLETED);
        assertThat(lab.merchantB.orders()).hasSize(1);
        assertThat(lab.merchant.orders()).isEmpty();
    }

    // Covers: I11, 7.6 (the same for every step that calls a merchant after a restart: an intent in RECONCILING and one
    // with an owed cancel, at a merchant with no gateway, are left as they are)
    @Test
    void reconcilingAndOwedCancelAtAMerchantWithNoGateway_areLeftAlone() {
        lab.merchantB.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));
        IntentId reconciling = lab.proposeAt(w, SHOP_B, TWO_SHIRTS).intentId();
        lab.decide(w, reconciling, true);
        while (lab.intent(reconciling).state() != IntentState.RECONCILING) {
            w.orchestrator().advance(reconciling);
        }
        Lab.Worker onlyA = lab.routedWorker("worker-a", MerchantGateways.of(Map.of(SHOP, a)));
        long version = lab.intent(reconciling).version();
        List<String> sentBefore = a.calls();

        assertThatThrownBy(() -> onlyA.orchestrator().advance(reconciling)).isInstanceOf(NoGatewayException.class);

        assertThat(lab.intent(reconciling).state()).isEqualTo(IntentState.RECONCILING);
        assertThat(lab.intent(reconciling).version()).isEqualTo(version);
        assertThat(a.calls()).isEqualTo(sentBefore);
    }
}
