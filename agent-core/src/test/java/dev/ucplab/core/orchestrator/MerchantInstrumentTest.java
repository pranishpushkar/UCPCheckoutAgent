package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.SHOP_B;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.json.CanonicalJson;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.MerchantGateways;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantInstruments;
import dev.ucplab.core.merchant.NoInstrumentException;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.FaultyMerchantModel;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The payment instrument belongs to the intent's merchant (19, H1 C3): the frozen complete body carries that merchant's
 * handler, type and credential type, is built once (I7), keeps the token to itself (I8), and a merchant with no
 * instrument sends nothing and changes nothing.
 */
class MerchantInstrumentTest {

    private static final String CANARY = FaultyMerchantModel.TOKEN_OK;
    private static final PaymentInstrument LAB_CARD = PaymentInstrument.mockCard("pi_a", "tok_ok");
    private static final PaymentInstrument SAMPLE_CARD = PaymentInstrument.sampleCard("pi_b", CANARY);
    /** B's instrument where B must also accept the payment: the fake merchant only knows the handler mock_pay_1. */
    private static final PaymentInstrument B_CARD = new PaymentInstrument("pi_b", "mock_pay_1", "card", "token", CANARY);

    private final Lab lab = new Lab();
    private final RecordingGateway a = new RecordingGateway(lab.merchant);
    private final RecordingGateway b = new RecordingGateway(lab.merchantB);
    private final Function<MerchantId, dev.ucplab.core.merchant.MerchantGateway> gateways =
            MerchantGateways.of(Map.of(SHOP, a, SHOP_B, b));

    @SuppressWarnings("unchecked")
    private static Map<String, Object> instrumentOf(MerchantAttempt complete) {
        Map<String, Object> root = CanonicalJson.readObject(complete.requestBody().orElseThrow().json());
        Map<String, Object> payment = (Map<String, Object>) root.get("payment");
        return ((List<Map<String, Object>>) payment.get("instruments")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> credential(Map<String, Object> instrument) {
        return (Map<String, Object>) instrument.get("credential");
    }

    private List<MerchantAttempt> completes(IntentId id) {
        return lab.intents.attempts(id).stream().filter(x -> x.op() == AttemptOp.COMPLETE).toList();
    }

    // Covers: 19, 7.4, I7 (a complete at B carries B's handler, type and credential type; a complete at A carries A's)
    @Test
    void aCompleteAtB_carriesBsHandlerTypeAndCredential_andAtAItsOwn() {
        Lab.Worker w = lab.routedWorker("worker-a", gateways,
                MerchantInstruments.of(Map.of(SHOP, LAB_CARD, SHOP_B, SAMPLE_CARD)));
        IntentId atB = lab.proposeAt(w, SHOP_B, TWO_SHIRTS).intentId();
        IntentId atA = lab.proposeAt(w, SHOP, TWO_SHIRTS).intentId();
        lab.decide(w, atB, true);
        lab.decide(w, atA, true);

        // The fake merchant at B knows only mock_pay_1, so B's complete is refused there; what is checked is what was sent.
        lab.drive(w, atB);
        assertThat(lab.drive(w, atA)).isEqualTo(COMPLETED);

        Map<String, Object> bi = instrumentOf(completes(atB).getFirst());
        assertThat(bi).containsEntry("handler_id", "mock_payment_handler").containsEntry("type", "card").containsEntry("id", "pi_b");
        assertThat(credential(bi)).containsEntry("type", "token");
        Map<String, Object> ai = instrumentOf(completes(atA).getFirst());
        assertThat(ai).containsEntry("handler_id", "mock_pay_1").containsEntry("type", "mock_card");
        assertThat(credential(ai)).containsEntry("type", "MOCK");
    }

    // Covers: I7, 7.4 (the frozen body is byte-identical across resends, and the instrument is asked for once per new
    // complete: a resend uses the stored body even if the merchant's instrument has changed since)
    @Test
    void theFrozenBody_isByteIdenticalAcrossResends_andTheInstrumentIsNotAskedAgain() {
        AtomicInteger asked = new AtomicInteger();
        Function<MerchantId, PaymentInstrument> changing = id -> {
            int n = asked.incrementAndGet();
            return n == 1 ? B_CARD : new PaymentInstrument("pi_other", "mock_pay_1", "card", "token", "tok_decline");
        };
        Lab.Worker w = lab.routedWorker("worker-a", gateways, changing);
        lab.merchantB.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.NotSent()));
        lab.merchantB.addRule(FaultRule.nth(MerchantCall.COMPLETE, 2, new Fault.NotSent()));
        IntentId id = lab.proposeAt(w, SHOP_B, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);

        assertThat(lab.drive(w, id)).isEqualTo(COMPLETED);

        assertThat(b.calls().stream().filter("complete"::equals).count()).as("complete calls at B").isGreaterThanOrEqualTo(3);
        assertThat(asked.get()).as("instrument lookups").isEqualTo(1);
        assertThat(completes(id)).extracting(x -> x.requestBody().orElseThrow().json()).containsOnly(
                completes(id).getFirst().requestBody().orElseThrow().json());
    }

    // Covers: I8 (the token appears in the frozen body only: not in the intent, its events, or the stored responses)
    @Test
    void theTokenCanary_appearsOnlyInTheFrozenBody() {
        Lab.Worker w = lab.routedWorker("worker-a", gateways,
                MerchantInstruments.of(Map.of(SHOP, LAB_CARD,
                        SHOP_B, new PaymentInstrument("pi_b", "mock_pay_1", "card", "token", "tok_canary_h1c3"))));
        IntentId id = lab.proposeAt(w, SHOP_B, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        lab.drive(w, id);

        String canary = "tok_canary_h1c3";
        assertThat(completes(id).getFirst().requestBody().orElseThrow().json()).contains(canary);
        assertThat(lab.intent(id).toString()).doesNotContain(canary);
        assertThat(lab.events(id).toString()).doesNotContain(canary);
        assertThat(lab.intents.attempts(id)).allSatisfy(x -> {
            assertThat(x.responseJson().orElse("")).doesNotContain(canary);
            assertThat(x.toString()).doesNotContain(canary);
        });
        assertThat(PaymentInstrument.sampleCard("pi_b", canary).toString()).doesNotContain(canary);
    }

    // Covers: I11, 19 (a merchant with no instrument: advance fails loudly before the write-ahead, nothing reaches any
    // gateway, state, version, attempts and events are unchanged, and it never uses another merchant's instrument; with
    // its instrument back the same intent completes)
    @Test
    void aMerchantWithNoInstrument_sendsNothing_andChangesNothing() {
        Lab.Worker w = lab.routedWorker("worker-a", gateways,
                MerchantInstruments.of(Map.of(SHOP, LAB_CARD, SHOP_B, B_CARD)));
        IntentId id = lab.proposeAt(w, SHOP_B, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        Lab.Worker onlyA = lab.routedWorker("worker-a", gateways, MerchantInstruments.of(Map.of(SHOP, LAB_CARD)));
        PurchaseIntent before = lab.intent(id);
        int events = lab.events(id).size();
        int attempts = lab.intents.attempts(id).size();
        List<String> bBefore = b.calls();

        assertThatThrownBy(() -> onlyA.orchestrator().advance(id)).isInstanceOf(NoInstrumentException.class)
                .hasMessage("no payment instrument for merchant shop-2");

        assertThat(b.calls()).isEqualTo(bBefore);
        assertThat(a.calls()).isEmpty();
        PurchaseIntent after = lab.intent(id);
        assertThat(after.state()).isEqualTo(before.state()).isEqualTo(IntentState.APPROVED);
        assertThat(after.version()).isEqualTo(before.version());
        assertThat(lab.intents.attempts(id)).hasSize(attempts);
        assertThat(lab.events(id)).hasSize(events);
        assertThat(lab.drive(w, id)).isEqualTo(COMPLETED);
        assertThat(lab.merchantB.orders()).hasSize(1);
    }
}
