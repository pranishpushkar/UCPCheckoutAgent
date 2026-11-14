package dev.ucplab.core.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.ProposedLine;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * What every {@link MerchantGateway} must answer, whoever implements it (Appendix B, 8.1, I4, I8): one suite run by the
 * in-memory model of the merchant, by {@code UcpHttpGateway} over answers in the lab merchant's wire shapes, and by
 * {@code UcpHttpGateway} over the official sample's recorded answers. A gateway that disagrees with another on the same
 * situation is a finding (H3 P2): the side that disagrees with DESIGN is fixed, never this class.
 *
 * <p>The suite talks only through {@link MerchantGateway}, so it lives in core. A subclass sets one situation up through
 * {@link #gatewayFor(Situation)}: a gateway that is ready to be asked, and what to ask. A situation the implementation
 * cannot produce is skipped there with the reason ({@link #unavailable(String)}); the reason shows in the test report.
 * Only what DESIGN says is asserted: the outcome kind, the status, the codes and the arithmetic of the view. Wording,
 * wire status codes of an {@code Ok} and ids are left to the implementation.
 */
public abstract class MerchantGatewayContract {

    /** A named state of a merchant, and what the gateway is then asked. */
    public enum Situation {
        /** A create with a buyer is answered ready, with lines, totals and currency. */
        CREATE_READY,
        /** A create is answered incomplete at a merchant that offers fulfillment; three updates select the shipping. */
        FULFILLMENT_FLOW,
        /** A create is answered with the status {@code requires_escalation}. */
        CREATE_REQUIRES_ESCALATION,
        /** A create where every item is unavailable. */
        CREATE_ALL_UNAVAILABLE,
        /** A complete on a ready session charges: completed, with an order. */
        COMPLETE_COMPLETED,
        /** A complete is declined and the merchant sends that as a 200 (the lab merchant). */
        COMPLETE_DECLINED_AS_200,
        /** A complete is declined and the merchant sends that as a 402 (the sample merchant). */
        COMPLETE_DECLINED_AS_402,
        /** A complete finds an item out of stock. */
        COMPLETE_OUT_OF_STOCK,
        /** A complete finds a changed price. */
        COMPLETE_PRICE_CHANGED,
        /** A complete (and a get) while another complete is charging. */
        COMPLETE_IN_PROGRESS,
        /** A get of a session the merchant does not know. */
        GET_UNKNOWN_SESSION,
        /** A get of a session that was completed. */
        GET_AFTER_COMPLETE,
        /** A cancel of an open session. */
        CANCEL_OPEN,
        /** The merchant answers every call with a checkout that is not the one asked for. */
        ANSWER_FOR_ANOTHER_SESSION,
        /** The merchant echoes the credential of the complete body into the messages of an answer. */
        CREDENTIAL_ECHOED_IN_ANSWER,
        /** The merchant echoes the credential of the complete body into the error of a refusal. */
        CREDENTIAL_ECHOED_IN_ERROR
    }

    /**
     * A gateway set up for one situation. {@code request} is what a create sends; {@code session} the session a call
     * after the create is about; {@code payment} the body of a complete (with {@code canary} as its token in the
     * credential situations).
     */
    public record Prepared(
            MerchantGateway gateway, CreateCheckout request, SessionId session, FrozenBody payment, Optional<String> canary) {

        public Prepared {
            java.util.Objects.requireNonNull(gateway, "gateway");
            java.util.Objects.requireNonNull(request, "request");
            java.util.Objects.requireNonNull(session, "session");
            java.util.Objects.requireNonNull(payment, "payment");
            java.util.Objects.requireNonNull(canary, "canary");
        }

        public static Prepared of(MerchantGateway gateway, CreateCheckout request, SessionId session, FrozenBody payment) {
            return new Prepared(gateway, request, session, payment, Optional.empty());
        }

        public Prepared withCanary(String token) {
            return new Prepared(gateway, request, session, payment, Optional.of(token));
        }
    }

    /** The situation cannot be produced by this implementation. */
    public static final class Unavailable extends Exception {
        public Unavailable(String reason) {
            super(reason);
        }
    }

    protected static Unavailable unavailable(String reason) {
        return new Unavailable(reason);
    }

    /** A gateway for {@code situation}, or {@link #unavailable(String) unavailable} with the reason. */
    protected abstract Prepared gatewayFor(Situation situation) throws Unavailable;

    private static final IntentId INTENT = new IntentId("intent-1");

    private static IdempotencyKey key(MutatingOp op, int revision) {
        return IdempotencyKey.derive(INTENT, op, revision);
    }

    private Prepared prepare(Situation situation) {
        try {
            return gatewayFor(situation);
        } catch (Unavailable u) {
            Assumptions.abort(situation + " cannot be produced here: " + u.getMessage());
            throw new AssertionError("unreachable");
        }
    }

    // ---------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static CallOutcome.Ok<CheckoutView> ok(CallOutcome<CheckoutView> outcome) {
        assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
        return (CallOutcome.Ok<CheckoutView>) outcome;
    }

    private static MerchantMessage only(CheckoutView view) {
        assertThat(view.messages()).hasSize(1);
        return view.messages().get(0);
    }

    /** The view's own money adds up: lines to subtotal, and subtotal, tax and fulfillment to the total (Appendix B). */
    private static void assertAddsUp(CheckoutView v) {
        assertThat(v.currency()).isNotBlank();
        assertThat(v.lines()).isNotEmpty().allSatisfy(l -> {
            assertThat(l.itemId()).isNotBlank();
            assertThat(l.quantity()).isPositive();
            assertThat(l.unitPriceMinor()).isPositive();
        });
        assertThat(v.lines().stream().mapToLong(l -> l.unitPriceMinor() * l.quantity()).sum())
                .as("subtotal is the sum of the lines").isEqualTo(v.subtotalMinor());
        assertThat(v.totalMinor()).as("total is subtotal, tax and fulfillment")
                .isEqualTo(v.subtotalMinor() + v.taxMinor() + v.fulfillmentMinor());
    }

    /** The answer's messages are the view's messages: the two lists never differ. */
    private static void assertMessagesAgree(CallOutcome.Ok<CheckoutView> ok) {
        assertThat(ok.messages()).isEqualTo(ok.value().messages());
    }

    // ---------------------------------------------------------------- the quote

    // Covers: FR2, 8.1, Appendix B (a create with a buyer is Ok and ready, with its lines, totals and currency)
    @Test
    void create_withBuyer_isOkAndReadyWithLinesTotalsAndCurrency() {
        Prepared p = prepare(Situation.CREATE_READY);

        CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().create(p.request(), key(MutatingOp.CREATE, 0)));

        assertThat(ok.httpStatus()).isIn(200, 201);
        CheckoutView v = ok.value();
        assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(v.order()).isEmpty();
        assertThat(v.id().value()).isNotBlank();
        assertThat(v.lines()).extracting(LineView::itemId)
                .containsExactlyElementsOf(p.request().lines().stream().map(ProposedLine::itemId).toList());
        assertAddsUp(v);
        assertMessagesAgree(ok);
        assertThat(v.messages()).noneMatch(m -> m.type().equals("error"));
    }

    // Covers: 7.2 shipping selection, 8.4, H1 C6, I6 (a create at a merchant that offers fulfillment is incomplete and the
    // view says the merchant offers it)
    @Test
    void create_atAFulfillmentMerchant_isIncompleteAndSaysFulfillmentIsOffered() {
        Prepared p = prepare(Situation.FULFILLMENT_FLOW);

        CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().create(p.request(), key(MutatingOp.CREATE, 0)));

        CheckoutView v = ok.value();
        assertThat(v.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(v.fulfillmentOffered()).as("fulfillmentOffered").isTrue();
        assertThat(v.fulfillment().flatMap(f -> f.methods().stream().findFirst())
                .flatMap(FulfillmentView.Method::selectedDestinationId)).as("nothing is selected yet").isEmpty();
        assertThat(v.order()).isEmpty();
        assertAddsUp(v);
        assertMessagesAgree(ok);
    }

    // Covers: 7.2 shipping selection, 8.4, H1 C6, I2 (the three selection steps, each built from the previous answer
    // only: at every step the answer keeps its fulfillment part, says fulfillment is offered, and shows what that step
    // unlocked; the last is ready, its fulfillment amount is the selected option's price and the total includes it)
    @Test
    void fulfillmentSelection_keepsTheFulfillmentPartAtEveryStep() {
        Prepared p = prepare(Situation.FULFILLMENT_FLOW);
        List<ProposedLine> lines = p.request().lines();
        Buyer buyer = Buyer.ofEmail(p.request().buyerEmail().orElseThrow());
        CheckoutView view = ok(p.gateway().create(p.request(), key(MutatingOp.CREATE, 0))).value();
        List<String> lineIds = view.lines().stream().flatMap(l -> l.lineId().stream()).toList();

        for (int step = 1; step <= 3; step++) {
            Optional<FulfillmentSelection> next =
                    FulfillmentSteps.next(view.fulfillment(), lineIds, FulfillmentPolicy.cheapest());
            assertThat(next).as("selection for step " + step).isPresent();
            QuoteShape shape = CheckoutWire.quoteShape(CheckoutWire.responseSummary(view)).orElseThrow();
            UpdateCheckout update = UpdateCheckout.from(lines, buyer, Optional.of(shape)).withFulfillment(next);

            CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().update(view.id(), update, key(MutatingOp.UPDATE, step - 1)));

            view = ok.value();
            String at = "step " + step;
            assertThat(ok.httpStatus()).as(at).isEqualTo(200);
            assertThat(view.fulfillmentOffered()).as(at + ": fulfillmentOffered").isTrue();
            assertThat(view.fulfillment()).as(at + ": the fulfillment part").isPresent();
            FulfillmentView.Method method = view.fulfillment().get().methods().get(0);
            assertThat(view.fulfillment().get().methods()).as(at).hasSize(1);
            assertThat(method.destinations()).as(at + ": destinations").isNotEmpty().allSatisfy(d -> {
                assertThat(d.id()).isNotBlank();
                assertThat(d.country()).isPresent();
            });
            assertThat(view.lines().stream().flatMap(l -> l.lineId().stream()).toList()).as(at + ": line ids").isEqualTo(lineIds);
            assertAddsUp(view);
            assertMessagesAgree(ok);
            if (step == 1) {
                assertThat(method.selectedDestinationId()).as(at).isEmpty();
                assertThat(method.groups()).as(at).isEmpty();
                assertThat(view.status()).as(at).isEqualTo(CheckoutStatus.INCOMPLETE);
            } else {
                assertThat(method.selectedDestinationId()).as(at + ": selected destination").isPresent();
                assertThat(method.destinations()).extracting(FulfillmentView.Destination::id)
                        .contains(method.selectedDestinationId().get());
                assertThat(method.groups()).as(at + ": groups").isNotEmpty().allSatisfy(g -> {
                    assertThat(g.id()).isNotBlank();
                    assertThat(g.options()).as(at + ": options").isNotEmpty().allSatisfy(o -> {
                        assertThat(o.id()).isNotBlank();
                        assertThat(o.priceMinor()).isNotNegative();
                    });
                });
            }
            if (step == 2) {
                assertThat(method.groups()).allSatisfy(g -> assertThat(g.selectedOptionId()).as(at).isEmpty());
                assertThat(view.status()).as(at).isEqualTo(CheckoutStatus.INCOMPLETE);
            }
            if (step == 3) {
                assertThat(method.groups()).allSatisfy(g -> assertThat(g.selectedOptionId()).as(at).isPresent());
                assertThat(view.status()).as(at).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
                long chosen = method.groups().stream().mapToLong(g -> g.options().stream()
                        .filter(o -> o.id().equals(g.selectedOptionId().get())).findFirst().orElseThrow().priceMinor()).sum();
                assertThat(view.fulfillmentMinor()).as(at + ": the fulfillment amount").isEqualTo(chosen);
            }
        }
        assertThat(FulfillmentSteps.next(view.fulfillment(), lineIds, FulfillmentPolicy.cheapest()))
                .as("nothing is left to select").isEmpty();
    }

    // Covers: 8.1, 7.2 (a create answered requires_escalation is an Ok with that status: not Uncertain, not a quote)
    @Test
    void create_answeredRequiresEscalation_isOkWithThatStatus() {
        Prepared p = prepare(Situation.CREATE_REQUIRES_ESCALATION);

        CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().create(p.request(), key(MutatingOp.CREATE, 0)));

        assertThat(ok.value().status()).isEqualTo(CheckoutStatus.REQUIRES_ESCALATION);
        assertThat(ok.value().order()).isEmpty();
        assertMessagesAgree(ok);
    }

    // Covers: A.1, 8.1, 7.2 create_rejected (every item unavailable is Rejected with an out_of_stock message: no session)
    @Test
    void create_everyItemUnavailable_isRejected() {
        Prepared p = prepare(Situation.CREATE_ALL_UNAVAILABLE);

        CallOutcome<CheckoutView> outcome = p.gateway().create(p.request(), key(MutatingOp.CREATE, 0));

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.Rejected.class, r -> {
            assertThat(r.httpStatus()).isEqualTo(200);
            assertThat(r.messages()).hasSize(1);
            MerchantMessage m = (MerchantMessage) r.messages().get(0);
            assertThat(m.type()).isEqualTo("error");
            assertThat(m.code()).isEqualTo("out_of_stock");
        });
    }

    // ---------------------------------------------------------------- complete

    // Covers: A.1, 7.2, I1, I2 (a complete that charges is Ok and completed, with the order and the quoted money)
    @Test
    void complete_thatCharges_isCompletedWithTheOrder() {
        Prepared p = prepare(Situation.COMPLETE_COMPLETED);

        CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().complete(p.session(), p.payment(), key(MutatingOp.COMPLETE, 0)));

        CheckoutView v = ok.value();
        assertThat(ok.httpStatus()).isEqualTo(200);
        assertThat(v.id()).isEqualTo(p.session());
        assertThat(v.status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(v.order()).isPresent();
        assertThat(v.order().get().id()).isNotBlank();
        assertAddsUp(v);
        assertMessagesAgree(ok);
        assertThat(v.messages()).noneMatch(m -> m.type().equals("error"));
    }

    // Covers: 8.1, 7.2, A.2, S7 (a decline sent as a 200 is Ok, ready_for_complete, with one payment_declined error)
    @Test
    void complete_declinedAs200_isReadyWithPaymentDeclined() {
        assertDeclined(prepare(Situation.COMPLETE_DECLINED_AS_200), 200);
    }

    // Covers: 8.1, 7.2, S7 (a decline sent as a 402 is reported exactly as the 200 form is: the merchant did not charge)
    @Test
    void complete_declinedAs402_isReportedLikeThe200Form() {
        assertDeclined(prepare(Situation.COMPLETE_DECLINED_AS_402), 402);
    }

    private static void assertDeclined(Prepared p, int wireStatus) {
        CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().complete(p.session(), p.payment(), key(MutatingOp.COMPLETE, 0)));

        CheckoutView v = ok.value();
        assertThat(ok.httpStatus()).isIn(200, wireStatus);
        assertThat(v.id()).isEqualTo(p.session());
        assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(v.order()).isEmpty();
        assertThat(only(v).type()).isEqualTo("error");
        assertThat(only(v).code()).isEqualTo("payment_declined");
        assertMessagesAgree(ok);
    }

    // Covers: 8.1, 7.2, A.1, S6 (out of stock at complete is Ok, ready_for_complete, with one out_of_stock error; no order)
    @Test
    void complete_outOfStock_isReadyWithOutOfStock() {
        Prepared p = prepare(Situation.COMPLETE_OUT_OF_STOCK);

        CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().complete(p.session(), p.payment(), key(MutatingOp.COMPLETE, 0)));

        CheckoutView v = ok.value();
        assertThat(ok.httpStatus()).isIn(200, 409);
        assertThat(v.id()).isEqualTo(p.session());
        assertThat(v.status()).isEqualTo(CheckoutStatus.READY_FOR_COMPLETE);
        assertThat(v.order()).isEmpty();
        assertThat(only(v).type()).isEqualTo("error");
        assertThat(only(v).code()).isEqualTo("out_of_stock");
        assertMessagesAgree(ok);
    }

    // Covers: 8.1, 7.2, A.1, S5 (a changed price at complete is Ok, incomplete, with one price_changed warning and the
    // refreshed quote; no order, nothing charged)
    @Test
    void complete_priceChanged_isIncompleteWithAWarningAndTheNewQuote() {
        Prepared p = prepare(Situation.COMPLETE_PRICE_CHANGED);

        CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().complete(p.session(), p.payment(), key(MutatingOp.COMPLETE, 0)));

        CheckoutView v = ok.value();
        assertThat(ok.httpStatus()).isEqualTo(200);
        assertThat(v.id()).isEqualTo(p.session());
        assertThat(v.status()).isEqualTo(CheckoutStatus.INCOMPLETE);
        assertThat(v.order()).isEmpty();
        assertThat(only(v).type()).isEqualTo("warning");
        assertThat(only(v).code()).isEqualTo("price_changed");
        assertAddsUp(v);
        assertMessagesAgree(ok);
    }

    // Covers: 8.1, 7.2, 7.5, A.1 (a complete while another is charging is Ok, complete_in_progress, one
    // complete_in_progress error, no order; a get shows the same status)
    @Test
    void complete_whileAnotherIsCharging_isInProgress() {
        Prepared p = prepare(Situation.COMPLETE_IN_PROGRESS);

        CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().complete(p.session(), p.payment(), key(MutatingOp.COMPLETE, 1)));
        CallOutcome.Ok<CheckoutView> read = ok(p.gateway().get(p.session()));

        CheckoutView v = ok.value();
        assertThat(ok.httpStatus()).isEqualTo(200);
        assertThat(v.id()).isEqualTo(p.session());
        assertThat(v.status()).isEqualTo(CheckoutStatus.COMPLETE_IN_PROGRESS);
        assertThat(v.order()).isEmpty();
        assertThat(only(v).type()).isEqualTo("error");
        assertThat(only(v).code()).isEqualTo("complete_in_progress");
        assertMessagesAgree(ok);
        assertThat(read.value().status()).isEqualTo(CheckoutStatus.COMPLETE_IN_PROGRESS);
        assertThat(read.value().order()).isEmpty();
    }

    // ---------------------------------------------------------------- reads and the rest

    // Covers: 8.1, A.1 (a get of an unknown session is a 404 ProtocolError, not retryable, with a lower-case code)
    @Test
    void get_unknownSession_isProtocolError404() {
        Prepared p = prepare(Situation.GET_UNKNOWN_SESSION);

        CallOutcome<CheckoutView> outcome = p.gateway().get(p.session());

        assertThat(outcome).isInstanceOfSatisfying(CallOutcome.ProtocolError.class, e -> {
            assertThat(e.httpStatus()).isEqualTo(404);
            assertThat(e.code()).isNotBlank().isEqualTo(e.code().toLowerCase(java.util.Locale.ROOT));
            assertThat(e.content()).isNotBlank();
            assertThat(e.retryAfter()).isEmpty();
        });
    }

    // Covers: 7.5, 8.1, A.1 (a get after a complete shows the session completed with the same order)
    @Test
    void get_afterComplete_isCompletedWithTheOrder() {
        Prepared p = prepare(Situation.GET_AFTER_COMPLETE);

        CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().get(p.session()));

        CheckoutView v = ok.value();
        assertThat(ok.httpStatus()).isEqualTo(200);
        assertThat(v.id()).isEqualTo(p.session());
        assertThat(v.status()).isEqualTo(CheckoutStatus.COMPLETED);
        assertThat(v.order()).isPresent();
        assertAddsUp(v);
        assertMessagesAgree(ok);
    }

    // Covers: A.1, 7.2 (a cancel of an open session is Ok and canceled, with no order)
    @Test
    void cancel_ofAnOpenSession_isCanceled() {
        Prepared p = prepare(Situation.CANCEL_OPEN);

        CallOutcome.Ok<CheckoutView> ok = ok(p.gateway().cancel(p.session(), key(MutatingOp.CANCEL, 0)));

        assertThat(ok.value().id()).isEqualTo(p.session());
        assertThat(ok.value().status()).isEqualTo(CheckoutStatus.CANCELED);
        assertThat(ok.value().order()).isEmpty();
        assertThat(ok.value().messages()).noneMatch(m -> m.type().equals("error"));
        assertMessagesAgree(ok);
    }

    // Covers: I4, 8.1 (an answer about another session than the one asked for is refused: never an Ok that the
    // orchestrator could take for the intent's session. A mutating call is Uncertain, because it may have been
    // processed; a get is a ProtocolError)
    @Test
    void anAnswerAboutAnotherSession_isRefused() {
        Prepared p = prepare(Situation.ANSWER_FOR_ANOTHER_SESSION);

        CallOutcome<CheckoutView> completed = p.gateway().complete(p.session(), p.payment(), key(MutatingOp.COMPLETE, 0));
        CallOutcome<CheckoutView> updated = p.gateway().update(p.session(),
                UpdateCheckout.of(p.request().lines(), p.request().buyerEmail()), key(MutatingOp.UPDATE, 0));
        CallOutcome<CheckoutView> canceled = p.gateway().cancel(p.session(), key(MutatingOp.CANCEL, 0));
        CallOutcome<CheckoutView> read = p.gateway().get(p.session());

        assertThat(completed).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(updated).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(canceled).isInstanceOf(CallOutcome.Uncertain.class);
        assertThat(read).isInstanceOf(CallOutcome.ProtocolError.class);
    }

    // ---------------------------------------------------------------- I8

    // Covers: I8, NFR6, 8.4 (a payment token that the merchant echoes into a message never comes back: not in a field, a
    // message, or the text of what the gateway returns)
    @Test
    void i8_aCredentialEchoedInAnAnswer_neverComesBack() {
        Prepared p = prepare(Situation.CREDENTIAL_ECHOED_IN_ANSWER);

        CallOutcome<CheckoutView> outcome = p.gateway().complete(p.session(), p.payment(), key(MutatingOp.COMPLETE, 0));

        assertNoCanary(p, outcome);
    }

    // Covers: I8, NFR6 (the same for a refusal: the token echoed into the error's code or content never comes back)
    @Test
    void i8_aCredentialEchoedInAnError_neverComesBack() {
        Prepared p = prepare(Situation.CREDENTIAL_ECHOED_IN_ERROR);

        CallOutcome<CheckoutView> outcome = p.gateway().complete(p.session(), p.payment(), key(MutatingOp.COMPLETE, 0));

        assertNoCanary(p, outcome);
    }

    private static void assertNoCanary(Prepared p, CallOutcome<CheckoutView> outcome) {
        String canary = p.canary().orElseThrow();
        assertThat(CheckoutWire.paymentToken(p.payment())).as("the body carries the canary").contains(canary);
        // A record's toString shows every field of it and of everything it holds, so this covers every field, message,
        // cause and content of the outcome.
        assertThat(outcome.toString()).doesNotContain(canary);
    }
}
