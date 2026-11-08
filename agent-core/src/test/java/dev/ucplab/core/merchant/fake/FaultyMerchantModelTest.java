package dev.ucplab.core.merchant.fake;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.MutableClock;
import dev.ucplab.core.id.SequentialIdGenerator;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.CreateCheckout;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.PaymentInstrument;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.UpdateCheckout;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FaultyMerchantModelTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final IntentId INTENT = new IntentId("intent-1");
    private static final IdempotencyKey CREATE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.CREATE, 0);
    private static final IdempotencyKey COMPLETE_KEY = IdempotencyKey.derive(INTENT, MutatingOp.COMPLETE, 0);
    private static final IdempotencyKey COMPLETE_KEY_1 = IdempotencyKey.derive(INTENT, MutatingOp.COMPLETE, 1);
    private static final FrozenBody PAY_OK = CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_ok"));
    private static final FrozenBody PAY_DECLINE =
            CheckoutWire.completeBody(PaymentInstrument.mockCard("pi_1", "tok_decline"));

    private final MutableClock clock = new MutableClock(T0);
    private final FaultyMerchantModel merchant =
            new FaultyMerchantModel(new MerchantId("shop-1"), clock, new SequentialIdGenerator("m"));

    private static CreateCheckout twoShirts() {
        return new CreateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("alice@example.com"));
    }

    private static CheckoutView ok(CallOutcome<CheckoutView> outcome) {
        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        return ((CallOutcome.Ok<CheckoutView>) outcome).value();
    }

    private static List<String> codes(CheckoutView view) {
        return view.messages().stream().map(MerchantMessage::code).toList();
    }

    private SessionId createSession() {
        return ok(merchant.create(twoShirts(), CREATE_KEY)).id();
    }

    // Covers: 14.1 Merchant strictness, A.1, I1 (LAB: a completed session is never charged again; STRICT: a new key
    // charges it again and makes a second order, while the same key still replays its stored result)
    @Test
    void complete_newKeyOnCompletedSession_chargesAgainOnlyInStrictMode() {
        SessionId lab = createSession();
        ok(merchant.complete(lab, PAY_OK, COMPLETE_KEY));
        ok(merchant.complete(lab, PAY_OK, COMPLETE_KEY_1));
        assertThat(merchant.orders()).hasSize(1);

        FaultyMerchantModel strict = new FaultyMerchantModel(new MerchantId("shop-1"), FaultyMerchantModel.Mode.STRICT,
                clock, new SequentialIdGenerator("s"));
        SessionId session = ok(strict.create(twoShirts(), CREATE_KEY)).id();
        CheckoutView first = ok(strict.complete(session, PAY_OK, COMPLETE_KEY));
        CheckoutView replay = ok(strict.complete(session, PAY_OK, COMPLETE_KEY));
        CheckoutView second = ok(strict.complete(session, PAY_OK, COMPLETE_KEY_1));

        assertThat(replay.order()).isEqualTo(first.order());
        assertThat(second.order()).isPresent().isNotEqualTo(first.order());
        assertThat(strict.orders()).hasSize(2).allSatisfy(o -> assertThat(o.sessionId()).isEqualTo(session));
        assertThat(strict.stock("item_123")).isEqualTo(96);
    }

    // Covers: A.1, A.2 (create prices at the catalog, adds 8% tax half up, is ready with items and an email)
    @Test
    void create_quotesCatalogPricesWithTax_andIsReady() {
        CallOutcome<CheckoutView> outcome = merchant.create(twoShirts(), CREATE_KEY);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Ok.class, o -> assertThat(o.httpStatus()).isEqualTo(201));
        CheckoutView view = ok(outcome);
        assertThat(view.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(view.subtotalMinor()).isEqualTo(5000);
        assertThat(view.taxMinor()).isEqualTo(400);
        assertThat(view.totalMinor()).isEqualTo(5400);
        assertThat(FaultyMerchantModel.tax(1206)).isEqualTo(96); // 96.48 rounds down
        assertThat(FaultyMerchantModel.tax(1250)).isEqualTo(100); // exactly 100.00
        assertThat(FaultyMerchantModel.tax(1256)).isEqualTo(100); // 100.48
        assertThat(FaultyMerchantModel.tax(1257)).isEqualTo(101); // 100.56
        assertThat(FaultyMerchantModel.tax(1262)).isEqualTo(101); // 100.96
    }

    // Covers: A.1 (state rule: no buyer email means incomplete)
    @Test
    void create_isIncomplete_withoutBuyerEmail() {
        CheckoutView view = ok(merchant.create(
                new CreateCheckout(List.of(new ProposedLine("item_123", 1)), Optional.empty()), CREATE_KEY));

        assertThat(view.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
    }

    // Covers: A.1, I4 (same key and same body returns the stored response; no second session)
    @Test
    void create_returnsStoredResponse_forSameKeyAndBody() {
        CheckoutView first = ok(merchant.create(twoShirts(), CREATE_KEY));
        merchant.setPrice("item_123", 9999);

        CheckoutView again = ok(merchant.create(twoShirts(), CREATE_KEY));

        assertThat(again).isEqualTo(first);
        assertThat(merchant.sessionsCreated()).isEqualTo(1);
    }

    // Covers: A.1, I7 (same key and a different body returns 409)
    @Test
    void create_returns409_forSameKeyWithDifferentBody() {
        merchant.create(twoShirts(), CREATE_KEY);

        CallOutcome<CheckoutView> outcome = merchant.create(
                new CreateCheckout(List.of(new ProposedLine("item_123", 3)), Optional.of("alice@example.com")),
                CREATE_KEY);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(409));
        assertThat(merchant.sessionsCreated()).isEqualTo(1);
    }

    // Covers: A.1, 8.1 (every item unavailable: no session id, reported as ProtocolError out_of_stock)
    @Test
    void create_failsClosedWithOutOfStock_whenEveryItemIsUnavailable() {
        CallOutcome<CheckoutView> outcome = merchant.create(new CreateCheckout(
                List.of(new ProposedLine("item_789", 4), new ProposedLine("nope", 1)), Optional.of("a@example.com")),
                CREATE_KEY);

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(200);
            assertThat(e.code()).isEqualTo("out_of_stock");
        });
        assertThat(merchant.sessionsCreated()).isZero();
    }

    // Covers: A.1 (a partly unavailable basket is created incomplete with a message per bad line)
    @Test
    void create_isIncompleteWithLineMessages_whenSomeItemsAreUnavailable() {
        CheckoutView view = ok(merchant.create(new CreateCheckout(
                List.of(new ProposedLine("item_123", 1), new ProposedLine("item_789", 4)),
                Optional.of("a@example.com")), CREATE_KEY));

        assertThat(view.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(codes(view)).containsExactly("out_of_stock");
    }

    // Covers: A.1, A.2, I1 (complete charges, creates one order and reserves stock)
    @Test
    void complete_createsOneOrder_andTakesStock() {
        SessionId id = createSession();

        CheckoutView view = ok(merchant.complete(id, PAY_OK, COMPLETE_KEY));

        assertThat(view.status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(view.order()).isPresent();
        assertThat(merchant.orders()).singleElement().satisfies(o -> {
            assertThat(o.orderId()).isEqualTo(view.order().get().id());
            assertThat(o.sessionId()).isEqualTo(id);
            assertThat(o.totalMinor()).isEqualTo(5400);
        });
        assertThat(merchant.stock("item_123")).isEqualTo(98);
    }

    // Covers: A.1, I1 (a second complete on a finished checkout, even with a new key, creates no order)
    @Test
    void complete_returnsSessionUnchanged_whenAlreadyCompleted() {
        SessionId id = createSession();
        CheckoutView first = ok(merchant.complete(id, PAY_OK, COMPLETE_KEY));

        CheckoutView second = ok(merchant.complete(id, PAY_OK, COMPLETE_KEY_1));

        assertThat(second.order()).isEqualTo(first.order());
        assertThat(merchant.orders()).hasSize(1);
    }

    // Covers: A.1, S5 (a changed price blocks completion: incomplete, price_changed, no charge)
    @Test
    void complete_returnsPriceChangedWithoutCharge_whenPriceMoved() {
        SessionId id = createSession();
        merchant.setPrice("item_123", 2600);

        CheckoutView view = ok(merchant.complete(id, PAY_OK, COMPLETE_KEY));

        assertThat(view.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(codes(view)).containsExactly("price_changed");
        assertThat(merchant.orders()).isEmpty();
        assertThat(merchant.stock("item_123")).isEqualTo(100);
    }

    // Covers: A.1, S5 (PUT re-prices at the current price and the session is ready again; a new key completes)
    @Test
    void update_repricesSession_soANewKeyCompletesAtTheNewPrice() {
        SessionId id = createSession();
        merchant.setPrice("item_123", 2600);
        ok(merchant.complete(id, PAY_OK, COMPLETE_KEY));

        CheckoutView updated = ok(merchant.update(id,
                new UpdateCheckout(List.of(new ProposedLine("item_123", 2)), Optional.of("alice@example.com")),
                IdempotencyKey.derive(INTENT, MutatingOp.UPDATE, 0)));
        CheckoutView done = ok(merchant.complete(id, PAY_OK, COMPLETE_KEY_1));

        assertThat(updated.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(updated.totalMinor()).isEqualTo(5616);
        assertThat(done.status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(merchant.orders()).singleElement().satisfies(o -> assertThat(o.totalMinor()).isEqualTo(5616));
    }

    // Covers: A.1, S6 (stock re-checked at complete: incomplete with out_of_stock, no order)
    @Test
    void complete_returnsOutOfStock_whenStockRanOutAfterQuote() {
        SessionId id = createSession();
        merchant.setStock("item_123", 1);

        CheckoutView view = ok(merchant.complete(id, PAY_OK, COMPLETE_KEY));

        assertThat(view.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(codes(view)).containsExactly("out_of_stock");
        assertThat(merchant.orders()).isEmpty();
    }

    // Covers: A.2, S7 (tok_decline: no order, stock released, session back to ready)
    @Test
    void complete_declinesAndReleasesStock_forDeclineToken() {
        SessionId id = createSession();

        CheckoutView view = ok(merchant.complete(id, PAY_DECLINE, COMPLETE_KEY));

        assertThat(view.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(codes(view)).containsExactly("payment_declined");
        assertThat(merchant.orders()).isEmpty();
        assertThat(merchant.stock("item_123")).isEqualTo(100);
    }

    // Covers: A.1 (cancel works on open sessions and is refused once completed)
    @Test
    void cancel_cancelsOpenSession_andIsRefusedWhenCompleted() {
        SessionId open = createSession();
        SessionId done = ok(merchant.create(twoShirts(), IdempotencyKey.derive(new IntentId("i2"), MutatingOp.CREATE, 0)))
                .id();
        ok(merchant.complete(done, PAY_OK, COMPLETE_KEY));

        CheckoutView canceled = ok(merchant.cancel(open, IdempotencyKey.derive(INTENT, MutatingOp.CANCEL, 0)));
        CheckoutView refused = ok(merchant.cancel(done, IdempotencyKey.derive(new IntentId("i2"), MutatingOp.CANCEL, 0)));

        assertThat(canceled.status()).isEqualTo(CheckoutStatus.CANCELED);
        assertThat(refused.status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(codes(refused)).containsExactly("checkout_not_cancelable");
    }

    // Covers: A.1 (GET of an unknown session is 404)
    @Test
    void get_returns404_forUnknownSession() {
        assertThat(merchant.get(new SessionId("cs_missing"))).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.httpStatus()).isEqualTo(404));
    }

    // Covers: 8.1, section 14 (not sent: the merchant sees nothing)
    @Test
    void notSent_neverReachesMerchant() {
        merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, new Fault.NotSent()));

        assertThat(merchant.create(twoShirts(), CREATE_KEY)).isInstanceOf(CallOutcome.NotSent.class);
        assertThat(merchant.sessionsCreated()).isZero();
        assertThat(merchant.delivered(MerchantCall.CREATE)).isZero();
        // The rule fired on the 1st call only; the retry goes through.
        assertThat(merchant.create(twoShirts(), CREATE_KEY)).isInstanceOf(CallOutcome.Ok.class);
    }

    // Covers: 5.4, S2, I1 (commit then drop: the order exists although the client saw Uncertain)
    @Test
    void commitThenDrop_createsOrder_butClientSeesUncertain() {
        SessionId id = createSession();
        merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.CommitThenDrop()));

        assertThat(merchant.complete(id, PAY_OK, COMPLETE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(merchant.orders()).hasSize(1);
        assertThat(ok(merchant.get(id)).status()).isEqualTo(CheckoutStatus.COMPLETED);
        // A resend with the same key replays the stored result: still one order.
        assertThat(ok(merchant.complete(id, PAY_OK, COMPLETE_KEY)).status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(merchant.orders()).hasSize(1);
    }

    // Covers: S3 (delayed response: committed, client Uncertain)
    @Test
    void delayResponse_commits_butClientSeesUncertain() {
        SessionId id = createSession();
        merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.DelayResponse()));

        assertThat(merchant.complete(id, PAY_OK, COMPLETE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(merchant.orders()).hasSize(1);
    }

    // Covers: S17, 7.5 (a request held at the proxy reaches the merchant only after the hold)
    @Test
    void delayForward_holdsRequest_untilClockPassesHold() {
        SessionId id = createSession();
        merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.DelayForward(Duration.ofSeconds(8))));

        assertThat(merchant.complete(id, PAY_OK, COMPLETE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(ok(merchant.get(id)).status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(merchant.orders()).isEmpty();

        clock.advance(Duration.ofSeconds(8));

        assertThat(ok(merchant.get(id)).status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(merchant.orders()).hasSize(1);
        assertThat(merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(1);
    }

    // Covers: S17, I1 (a resend that overtakes a held request, same key: one order, held one gets the stored result)
    @Test
    void delayForward_thenResendSameKey_createsOneOrder() {
        SessionId id = createSession();
        merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.DelayForward(Duration.ofSeconds(8))));
        merchant.complete(id, PAY_OK, COMPLETE_KEY);

        ok(merchant.complete(id, PAY_OK, COMPLETE_KEY));
        clock.advance(Duration.ofSeconds(9));

        assertThat(merchant.orders()).hasSize(1);
        assertThat(merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(2);
    }

    // Covers: A.3, 7.2 (payment latency: complete_in_progress is visible, duplicates wait, then completed)
    @Test
    void holdInProgress_showsCompleteInProgress_thenCompletes() {
        SessionId id = createSession();
        merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.HoldInProgress(Duration.ofSeconds(3))));

        assertThat(merchant.complete(id, PAY_OK, COMPLETE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(ok(merchant.get(id)).status()).isEqualTo(CheckoutStatus.COMPLETE_IN_PROGRESS);
        assertThat(merchant.complete(id, PAY_OK, COMPLETE_KEY)).isInstanceOf(CallOutcome.Uncertain.class);
        CheckoutView busy = ok(merchant.update(id,
                new UpdateCheckout(List.of(new ProposedLine("item_123", 1)), Optional.of("a@example.com")),
                IdempotencyKey.derive(INTENT, MutatingOp.UPDATE, 0)));
        assertThat(codes(busy)).containsExactly("checkout_busy");

        clock.advance(Duration.ofSeconds(3));

        assertThat(ok(merchant.get(id)).status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(ok(merchant.complete(id, PAY_OK, COMPLETE_KEY)).status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(merchant.orders()).hasSize(1);
    }

    // Covers: S13, I1 (duplicate request: delivered twice, one order, client gets the first response)
    @Test
    void duplicate_deliversTwice_butCreatesOneOrder() {
        SessionId id = createSession();
        merchant.addRule(FaultRule.nth(MerchantCall.COMPLETE, 1, new Fault.Duplicate()));

        assertThat(ok(merchant.complete(id, PAY_OK, COMPLETE_KEY)).status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(merchant.delivered(MerchantCall.COMPLETE)).isEqualTo(2);
        assertThat(merchant.orders()).hasSize(1);
    }

    // Covers: S16, section 14 (merchant-side price change on every complete, combined with normal delivery)
    @Test
    void priceChange_appliesBeforeEachMatchingCall() {
        SessionId id = createSession();
        merchant.addRule(FaultRule.every(MerchantCall.COMPLETE, new Fault.PriceChange("item_123", 2700)));

        CheckoutView view = ok(merchant.complete(id, PAY_OK, COMPLETE_KEY));

        assertThat(codes(view)).containsExactly("price_changed");
        assertThat(merchant.price("item_123")).isEqualTo(2700);
    }

    // Covers: 8.1 (fail_before_forward with a status: ProtocolError, merchant untouched)
    @Test
    void reject_returnsProtocolError_withoutReachingMerchant() {
        merchant.addRule(FaultRule.every(MerchantCall.CREATE,
                new Fault.Reject(503, "unavailable", Optional.of(Duration.ofSeconds(1)))));

        assertThat(merchant.create(twoShirts(), CREATE_KEY)).isInstanceOfSatisfying(CallOutcome.ProtocolError.class,
                e -> assertThat(e.retryAfter()).contains(Duration.ofSeconds(1)));
        assertThat(merchant.delivered(MerchantCall.CREATE)).isZero();
    }
}
