package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static dev.ucplab.core.orchestrator.RewritingGateway.always;
import static dev.ucplab.core.orchestrator.RewritingGateway.as;
import static dev.ucplab.core.orchestrator.RewritingGateway.error;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.ContinueUrl;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantMessage;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * H5 C7a: the handoff rows of table 7.2, one by one, at an owned MCP store whose {@code completion} is {@code handoff}
 * (19). The lab merchant answers; {@link RewritingGateway} changes only what the orchestrator is told.
 */
class HandoffTest {

    static final Duration FIVE_MINUTES = Duration.ofMinutes(5);

    static final MerchantEntry HANDOFF = new MerchantEntry(SHOP, URI.create("https://shop.example"), "Shop",
            MerchantStatus.ACTIVE, Transport.MCP, Completion.HANDOFF, true, Optional.of("shop.example"));
    static final String URL = "https://shop.example/cart/c/abc?key=k-secret-1";

    protected final Lab lab = newLab();

    /**
     * A lab over the store under test: the in-memory adapters here, SQLite in agent-persistence's
     * {@code SqlHandoffTest} (port parity, H5 C7b).
     */
    protected Lab newLab() {
        return new Lab();
    }

    HandoffTest() {
        lab.entries = id -> id.equals(SHOP) ? Optional.of(HANDOFF) : Optional.empty();
    }

    /** The answer with {@code url} as its continue_url (none when empty), and {@code change} applied to the view. */
    static UnaryOperator<CallOutcome<CheckoutView>> view(Optional<String> url, UnaryOperator<CheckoutView> change) {
        return outcome -> {
            if (!(outcome instanceof CallOutcome.Ok<CheckoutView> ok)) {
                return outcome;
            }
            CheckoutView v = change.apply(ok.value());
            return new CallOutcome.Ok<>(ok.httpStatus(), new CheckoutView(v.id(), v.status(), v.currency(), v.lines(),
                    v.subtotalMinor(), v.taxMinor(), v.totalMinor(), v.order(), v.buyer(), v.messages(), v.fulfillment(),
                    v.fulfillmentMinor(), v.fulfillmentOffered(), url.map(u -> new ContinueUrl(URI.create(u)))),
                    ok.messages());
        };
    }

    static UnaryOperator<CallOutcome<CheckoutView>> withUrl(String url) {
        return view(Optional.of(url), v -> v);
    }

    static CheckoutView status(CheckoutView v, CheckoutStatus status) {
        return new CheckoutView(v.id(), status, v.currency(), v.lines(), v.subtotalMinor(), v.taxMinor(), v.totalMinor(),
                v.order(), v.buyer(), v.messages(), v.fulfillment(), v.fulfillmentMinor(), v.fulfillmentOffered());
    }

    static CheckoutView withoutMessages(CheckoutView v) {
        return new CheckoutView(v.id(), v.status(), v.currency(), v.lines(), v.subtotalMinor(), v.taxMinor(), v.totalMinor(),
                v.order(), v.buyer(), List.of(), v.fulfillment(), v.fulfillmentMinor(), v.fulfillmentOffered());
    }

    static CheckoutView total(CheckoutView v, long total) {
        return new CheckoutView(v.id(), v.status(), v.currency(), v.lines(), v.subtotalMinor(), v.taxMinor(), total,
                v.order(), v.buyer(), v.messages(), v.fulfillment(), v.fulfillmentMinor(), v.fulfillmentOffered());
    }

    private List<String> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).map(IntentEvent::payloadJson).toList();
    }

    private List<String> transitions(IntentId id) {
        return events(id, "transition");
    }

    private IntentId approved(Lab.Worker worker) {
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        lab.decide(worker, id, true);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
        return id;
    }

    private void neverCompleted(IntentId id) {
        assertThat(lab.intents.attempts(id)).noneMatch(a -> a.op() == AttemptOp.COMPLETE);
        assertThat(lab.merchant.orders()).isEmpty();
        assertThat(transitions(id)).noneMatch(t -> t.contains("SEND_COMPLETE") || t.contains("COMPLETING"));
    }

    // ---------------------------------------------------------------- QUOTING

    // Covers: 7.2 quote_ready (handoff), I2 (an incomplete answer whose messages are recoverable buyer codes is a quote
    // at a handoff merchant: the approval opens on it)
    @Test
    void quoteReady_atAHandoffMerchant_acceptsAnIncompleteAnswerWithBuyerCodes() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.INCOMPLETE, false, error("buyer_identity_contact_method_required"),
                        error("delivery_address_required"),
                        error("delivery_no_delivery_available_for_merchandise_line"))));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(transitions(id)).contains(
                "{\"event\":\"QUOTE_READY\",\"from\":\"QUOTING\",\"guard\":\"QUOTE_POLICY_ALLOWS\",\"to\":\"AWAITING_APPROVAL\"}");
    }

    // Covers: 7.2 quote_ready (handoff) (a message that is neither recoverable nor one of the three buyer codes is no
    // quote: the answer ends quote_unsupported)
    @Test
    void quoteReady_atAHandoffMerchant_refusesAnotherMessage() {
        MerchantMessage blocking = new MerchantMessage("error", "payment_required", Optional.empty(), "x",
                Optional.of("requires_buyer_input"));
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.INCOMPLETE, false, blocking)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_unsupported");
    }

    /** The bodies of the intent's creates and updates, in order, as frozen and sent. */
    private List<String> sentBodies(IntentId id) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == AttemptOp.CREATE || a.op() == AttemptOp.UPDATE)
                .map(a -> a.requestBody().orElseThrow().json()).toList();
    }

    // Covers: 8.4 (no buyer identity at a handoff merchant), 19, I8 (the create and the re-quote's update send no buyer:
    // the buyer gives it on the merchant's page; H5 Stage 3, where the store refused the local sign-in's email)
    @Test
    void aHandoffCreateAndUpdate_sendNoBuyer() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET,
                view(Optional.of(URL), v -> total(v, v.totalMinor() + 1))));
        IntentId id = approved(worker);
        worker.orchestrator().advance(id);
        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);

        assertThat(lab.intents.attempts(id)).extracting(a -> a.op()).contains(AttemptOp.CREATE, AttemptOp.UPDATE);
        assertThat(sentBodies(id)).isNotEmpty().allSatisfy(body -> {
            assertThat(body).doesNotContain("\"buyer\"");
            assertThat(body).doesNotContain(lab.alice().email());
        });
    }

    // Covers: 8.4, 9.1 (a handoff merchant that needs shipping: the selection updates send no buyer either. The lab
    // merchant says "buyer email is required" while it has none, and a selection is sent only on an answer without
    // messages, so the rewrite drops them)
    @Test
    void aHandoffSelectionUpdate_sendsNoBuyer() {
        lab.merchant.enableFulfillment();
        UnaryOperator<CallOutcome<CheckoutView>> noMessages = outcome -> outcome instanceof CallOutcome.Ok<CheckoutView> ok
                ? new CallOutcome.Ok<>(ok.httpStatus(), withoutMessages(ok.value()), List.of()) : outcome;
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE, noMessages)
                .rewrite(MerchantCall.UPDATE, noMessages));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        for (int i = 0; i < 20 && lab.intent(id).state() == IntentState.QUOTING; i++) {
            worker.orchestrator().advance(id);
        }

        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.UPDATE).isNotEmpty();
        assertThat(sentBodies(id)).allSatisfy(body -> assertThat(body).doesNotContain("\"buyer\""));
    }

    // Covers: 8.4 (an agent merchant still receives buyer.email: only handoff merchants drop it)
    @Test
    void anAgentMerchantCreate_stillSendsTheEmail() {
        lab.entries = null;
        Lab.Worker worker = lab.worker("w");
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(sentBodies(id)).first().asString()
                .contains("\"buyer\":{\"email\":\"" + lab.alice().email() + "\"}");
    }

    // Covers: 7.2 quote_unsupported (handoff) (a readable quote with a message that is neither recoverable nor on the
    // allowlist ends quote_unsupported at once, not as exhausted; the session is cancelled; the event names the status
    // and the blocking code only), 7.6, I8
    @Test
    void aQuoteWithAnUnrecoverableMessage_endsQuoteUnsupported_andCancels() {
        MerchantMessage blocking = new MerchantMessage("error", "payment_required", Optional.empty(), "Pay first",
                Optional.of("unrecoverable"));
        // Neither of these blocks: one is recoverable (not on the allowlist), one is on the allowlist (not recoverable).
        MerchantMessage recoverable = new MerchantMessage("warning", "quantity_note", Optional.empty(), "x",
                Optional.of("recoverable"));
        MerchantMessage allowlisted = new MerchantMessage("error", "delivery_address_required", Optional.empty(), "x",
                Optional.of("requires_buyer_input"));
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.INCOMPLETE, false, recoverable, allowlisted, blocking)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_unsupported");
        assertThat(lab.intent(id).cancelPending()).isTrue();
        assertThat(events(id, "quote_unsupported"))
                .containsExactly("{\"codes\":[\"payment_required\"],\"status\":\"incomplete\"}");

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.merchant.session(lab.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                .isEqualTo(CheckoutStatus.CANCELED);
        neverCompleted(id);
    }

    // Covers: 7.2 quote_unsupported (handoff) (a status the buyer cannot finish from, canceled here, is no quote either)
    @Test
    void aQuoteWithAnUnhandledStatus_endsQuoteUnsupported() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.CANCELED, false)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_unsupported");
        assertThat(events(id, "quote_unsupported")).containsExactly("{\"codes\":[],\"status\":\"canceled\"}");
    }

    // Covers: 7.2 (at an agent merchant an answer that is neither a quote nor a known failure still ends
    // quote_retries_exhausted: quote_unsupported is for handoff merchants only, follow-up 6 of PR #19)
    @Test
    void anAgentMerchant_unusableAnswer_staysQuoteRetriesExhausted() {
        lab.entries = null;
        MerchantMessage blocking = new MerchantMessage("error", "payment_required", Optional.empty(), "x",
                Optional.of("unrecoverable"));
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.INCOMPLETE, false, blocking)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.intent(id).failureReason()).contains("quote_retries_exhausted");
        assertThat(events(id, "quote_unsupported")).isEmpty();
    }

    // Covers: 7.2 create_rejected (a refused create, as the store refused the Stage 3 rerun's: FAILED_NOT_CHARGED,
    // create_rejected; no session, so no cancel and no orphan probe; event quote_refused with the code only), I8
    @Test
    void aRefusedCreate_endsCreateRejected_withItsCode_andNoProbe() {
        MerchantMessage refused = new MerchantMessage("error", "buyer_identity_email_is_invalid",
                Optional.of("$.buyer.email"), "Email is invalid", Optional.of("unrecoverable"));
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                always(new CallOutcome.Rejected<>(200, List.of(refused)))));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);

        assertThat(lab.intent(id).failureReason()).contains("create_rejected");
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(lab.intents.attempts(id)).extracting(a -> a.op()).containsExactly(AttemptOp.CREATE);
        assertThat(events(id, "quote_refused")).containsExactly("{\"codes\":[\"buyer_identity_email_is_invalid\"]}");
        assertThat(events(id, "quote_refused").getFirst()).doesNotContain("Email is invalid");
    }

    // Covers: 7.2 quote_ready (handoff) (a ready_for_complete answer with no blocking message is a quote at a handoff
    // merchant too: the lab merchant answers incomplete there since no email is sent (8.4), so this is forced)
    @Test
    void quoteReady_atAHandoffMerchant_acceptsAReadyForCompleteAnswer() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.READY_FOR_COMPLETE, false)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
    }

    // Covers: 7.2 quote_ready (handoff) (a ready_for_complete answer is held to the same message rule: one with a
    // blocking message is no quote at a handoff merchant, though it would be at an agent merchant)
    @Test
    void quoteReady_atAHandoffMerchant_holdsReadyForCompleteToTheMessageRule() {
        MerchantMessage blocking = new MerchantMessage("error", "payment_required", Optional.empty(), "x",
                Optional.of("requires_buyer_input"));
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.READY_FOR_COMPLETE, false, blocking)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_unsupported");
    }

    // Covers: 7.2 quote_ready (handoff) (an unrecoverable buyer code is not enough: every message must be recoverable or
    // one of the three codes, and a recoverable message with another code is accepted)
    @Test
    void quoteReady_atAHandoffMerchant_readsSeverityAndCode() {
        MerchantMessage recoverableOther = new MerchantMessage("error", "some_code", Optional.empty(), "x",
                Optional.of("recoverable"));
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.INCOMPLETE, false, recoverableOther)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);

        MerchantMessage buyerUnrecoverable = new MerchantMessage("error", "delivery_address_required", Optional.empty(),
                "x", Optional.of("unrecoverable"));
        Lab.Worker second = lab.worker("w2", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.INCOMPLETE, false, buyerUnrecoverable)));
        IntentId other = second.orchestrator().propose(lab.alice(), SHOP, List.of(
                new dev.ucplab.core.intent.ProposedLine("item_456", 1)), "buy").intentId();
        assertThat(lab.drive(second, other)).isEqualTo(IntentState.AWAITING_APPROVAL);

        MerchantMessage neither = new MerchantMessage("error", "some_code", Optional.empty(), "x", Optional.empty());
        Lab.Worker third = lab.worker("w3", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.INCOMPLETE, false, neither)));
        IntentId refused = third.orchestrator().propose(lab.bob(), SHOP, TWO_SHIRTS, "buy").intentId();
        assertThat(lab.drive(third, refused)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
    }

    // Covers: 7.2 requires_escalation (handoff) (at a handoff merchant the buyer finishing on the merchant's page is
    // the plan: a create that answers requires_escalation opens the approval, through its own row)
    @Test
    void requiresEscalation_atAHandoffMerchant_opensTheApproval() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.REQUIRES_ESCALATION, false)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(transitions(id)).contains("{\"event\":\"REQUIRES_ESCALATION\",\"from\":\"QUOTING\","
                + "\"guard\":\"QUOTE_POLICY_ALLOWS\",\"to\":\"AWAITING_APPROVAL\"}");
    }

    /** {@code first}, then {@code then}, on the same answer. */
    private static UnaryOperator<CallOutcome<CheckoutView>> both(UnaryOperator<CallOutcome<CheckoutView>> first,
            UnaryOperator<CheckoutView> then) {
        return o -> view(Optional.empty(), then).apply(first.apply(o));
    }

    // Covers: 7.2 requires_escalation (handoff), 8.1, H5 C8 (an update that answers requires_escalation at a handoff
    // merchant opens the approval too: the re-quote after hand_off_changed, through the same row)
    @Test
    void requiresEscalation_onTheUpdate_atAHandoffMerchant_opensTheApproval() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant)
                .rewrite(MerchantCall.GET, view(Optional.of(URL), v -> total(v, v.totalMinor() + 1)))
                .rewrite(MerchantCall.UPDATE, as(CheckoutStatus.REQUIRES_ESCALATION, false)));
        IntentId id = approved(worker);

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(lab.intents.attempts(id)).extracting(a -> a.op())
                .containsExactly(AttemptOp.CREATE, AttemptOp.GET, AttemptOp.UPDATE);
        assertThat(transitions(id)).contains("{\"event\":\"REQUIRES_ESCALATION\",\"from\":\"QUOTING\","
                + "\"guard\":\"QUOTE_POLICY_ALLOWS\",\"to\":\"AWAITING_APPROVAL\"}");
        neverCompleted(id);
    }

    // Covers: 7.2 requires_escalation (handoff), I2, Q2, H5 C8 (the row holds but for the status: lines that are not the
    // proposal's are refused as for any quote, never approved)
    @Test
    void requiresEscalation_atAHandoffMerchant_withOtherLines_isRefused() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                both(as(CheckoutStatus.REQUIRES_ESCALATION, false),
                        v -> withLines(v, List.of(v.lines().getFirst(), v.lines().getFirst())))));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_lines_differ");
        assertThat(lab.approvals.findLatestForSystem(id)).isEmpty();
    }

    // Covers: 7.2 requires_escalation (handoff), 9.1 P2, H5 C8 (the row holds but for the status: a total the policy
    // refuses is refused, never approved)
    @Test
    void requiresEscalation_atAHandoffMerchant_overTheCap_isRefusedByPolicy() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                both(as(CheckoutStatus.REQUIRES_ESCALATION, false), v -> total(v, 1_000_000))));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).hasValueSatisfying(r -> assertThat(r).startsWith("policy_"));
        assertThat(lab.approvals.findLatestForSystem(id)).isEmpty();
    }

    // Covers: 7.2 requires_escalation (handoff), 8.1, H5 C8 (the row holds but for the status: a message that is neither
    // recoverable nor one of the three buyer codes makes it no quote, as at quote_ready (handoff))
    @Test
    void requiresEscalation_atAHandoffMerchant_withABlockingMessage_isNoQuote() {
        MerchantMessage blocking = new MerchantMessage("error", "payment_required", Optional.empty(), "x",
                Optional.of("requires_buyer_input"));
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.REQUIRES_ESCALATION, false, blocking)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_unsupported");
        assertThat(lab.approvals.findLatestForSystem(id)).isEmpty();
    }

    // Covers: 7.2 (8.1's rule stands for agent merchants: requires_escalation ends QUOTING there)
    @Test
    void requiresEscalation_atAnAgentMerchant_stillEndsQuoting() {
        lab.entries = null;   // every merchant an owned lab entry: completion agent
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.REQUIRES_ESCALATION, false)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("requires_escalation");
    }

    // ---------------------------------------------------------------- APPROVED

    // Covers: 7.2 hand_off, Handoff merchants [CONTRACT], I1, I8, 10 (send_complete is never taken: the GET shows the
    // approved quote and a continue_url on the shop's domain, so the intent is HANDED_OFF with the URL stored, the
    // approval consumed, no complete attempt, and an event with the host only)
    @Test
    void handOff_storesTheUrl_consumesTheApproval_andNeverCompletes() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, withUrl(URL)));
        IntentId id = approved(worker);

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.HANDED_OFF);

        PurchaseIntent intent = lab.intent(id);
        assertThat(intent.handoffUrl()).contains(new ContinueUrl(URI.create(URL)));
        assertThat(intent.cancelPending()).isFalse();
        assertThat(intent.failureReason()).isEmpty();
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.CONSUMED);
        neverCompleted(id);
        assertThat(transitions(id)).contains(
                "{\"event\":\"HAND_OFF\",\"from\":\"APPROVED\",\"guard\":\"NONE\",\"to\":\"HANDED_OFF\"}");
        assertThat(events(id, "handed_off")).containsExactly("{\"host\":\"shop.example\"}");
        assertThat(events(id, "approval_decision_recovered")).as("an approval already APPROVED is not recovered").isEmpty();
        assertThat(lab.events(id)).noneMatch(e -> e.payloadJson().contains("k-secret-1"));
    }

    // Covers: 7.2 hand_off (requires_escalation is a status the GET may show, as are incomplete and ready_for_complete)
    @Test
    void handOff_acceptsEachBuyerStatus() {
        for (CheckoutStatus s : List.of(CheckoutStatus.INCOMPLETE, CheckoutStatus.READY_FOR_COMPLETE,
                CheckoutStatus.REQUIRES_ESCALATION)) {
            Lab one = newLab();
            one.entries = lab.entries;
            Lab.Worker worker = one.worker("w", new RewritingGateway(one.merchant).rewrite(MerchantCall.GET,
                    view(Optional.of(URL), v -> status(v, s))));
            IntentId id = worker.orchestrator().propose(one.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
            one.drive(worker, id);
            one.decide(worker, id, true);

            assertThat(one.drive(worker, id)).as(s.name()).isEqualTo(IntentState.HANDED_OFF);
        }
    }

    // Covers: 7.2 Handoff merchants [CONTRACT] (approval_invalid applies before the GET: nothing is read, nothing sent)
    @Test
    void anInvalidApproval_isEscalatedBeforeTheGet() {
        RecordingGateway recording = new RecordingGateway(
                new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, withUrl(URL)));
        Lab.Worker worker = lab.worker("w", recording);
        IntentId id = approved(worker);
        var approval = lab.approvals.findLatestForSystem(id).orElseThrow();
        lab.approvals.transition(approval.id(), ApprovalStatus.APPROVED, ApprovalStatus.EXPIRED, lab.clock.instant(),
                Optional.empty());
        int before = recording.calls().size();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("approval_invalid");
        assertThat(recording.calls().subList(before, recording.calls().size()))
                .as("no GET, no cancel, no complete").isEmpty();
    }

    // Covers: 7.2 hand_off_changed (another total re-quotes on the same session, requote_count + 1; a new approval is
    // required)
    @Test
    void handOffChanged_requotes() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET,
                view(Optional.of(URL), v -> total(v, v.totalMinor() + 1))));
        IntentId id = approved(worker);

        worker.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.QUOTING);
        assertThat(lab.intent(id).requoteCount()).isEqualTo(1);
        assertThat(lab.intent(id).approvedSnapshotHash()).isEmpty();
        assertThat(transitions(id)).contains("{\"event\":\"HAND_OFF_CHANGED\",\"from\":\"APPROVED\","
                + "\"guard\":\"REQUOTE_BELOW_CAP\",\"to\":\"QUOTING\"}");
        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        neverCompleted(id);
    }

    // Covers: 7.2 hand_off_changed (the GET is recorded as one get attempt: no key, no body, RESPONDED, its response naming
    // no lines; it starts the new cycle, so the re-quote sends its update instead of counting the first create as tried;
    // the update resends the line ids of the create's answer, not of the get)
    @Test
    void handOffChanged_recordsOneGetAttempt_asTheCycleStart() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET,
                view(Optional.of(URL), v -> total(v, v.totalMinor() + 1))));
        IntentId id = approved(worker);

        worker.orchestrator().advance(id);

        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.GET).singleElement().satisfies(g -> {
            assertThat(g.idempotencyKey()).isEmpty();
            assertThat(g.requestBody()).isEmpty();
            assertThat(g.requestHash()).isEmpty();
            assertThat(g.status()).isEqualTo(dev.ucplab.core.intent.AttemptStatus.RESPONDED);
            assertThat(g.revision()).isZero();
            // The lab merchant is ready only once it holds an email (Appendix A); none is sent here (8.4).
            assertThat(g.responseJson()).contains("{\"hand_off\":\"changed\",\"status\":\"incomplete\","
                    + "\"total_minor\":5401}");
        });
        lab.drive(worker, id);
        assertThat(lab.intents.attempts(id)).extracting(a -> a.op())
                .containsExactly(AttemptOp.CREATE, AttemptOp.GET, AttemptOp.UPDATE);
    }

    // Covers: 7.2 hand_off rows (only the re-quote's GET is recorded: a hand_off, a gone, a refused, an unexpected or a
    // failed read writes no get attempt)
    @Test
    void noOtherHandOffRead_recordsAGetAttempt() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, withUrl(URL)));
        IntentId id = approved(worker);
        lab.drive(worker, id);

        assertThat(lab.intents.attempts(id)).noneMatch(a -> a.op() == AttemptOp.GET);
    }

    // Covers: 7.2 hand_off_changed, P8 (at the cap: REJECTED with price_unstable, and the session is cancelled)
    @Test
    void handOffChanged_atTheCap_rejectsPriceUnstable() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET,
                view(Optional.of(URL), v -> total(v, v.totalMinor() + 1))));
        IntentId id = approved(worker);
        for (int round = 0; lab.intent(id).state() != IntentState.REJECTED; round++) {
            assertThat(round).as("rounds").isLessThan(10);
            if (lab.intent(id).state() == IntentState.AWAITING_APPROVAL) {
                lab.decide(worker, id, true);
            }
            worker.orchestrator().advance(id);
        }

        assertThat(lab.intent(id).failureReason()).contains("price_unstable");
        assertThat(lab.intent(id).requoteCount()).isEqualTo(lab.policyConfig.maxRequotes());
        assertThat(lab.intent(id).cancelPending()).isTrue();
        neverCompleted(id);
    }

    // Covers: 7.2 hand_off_gone (canceled, or not found as a 404: FAILED_NOT_CHARGED, session_gone, nothing to cancel)
    @Test
    void handOffGone_failsNotCharged_withNothingToCancel() {
        for (UnaryOperator<CallOutcome<CheckoutView>> gone : List.of(
                view(Optional.of(URL), v -> status(v, CheckoutStatus.CANCELED)),
                always(new CallOutcome.ProtocolError<>(404, "not_found", "", Optional.empty())))) {
            Lab one = newLab();
            one.entries = lab.entries;
            Lab.Worker worker = one.worker("w", new RewritingGateway(one.merchant).rewrite(MerchantCall.GET, gone));
            IntentId id = worker.orchestrator().propose(one.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
            one.drive(worker, id);
            one.decide(worker, id, true);

            assertThat(one.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(one.intent(id).failureReason()).contains("session_gone");
            assertThat(one.intent(id).cancelPending()).isFalse();
            assertThat(one.intents.attempts(id)).noneMatch(a -> a.op() == AttemptOp.CANCEL);
            assertThat(one.events(id).stream().filter(e -> e.type().equals("transition")).map(IntentEvent::payloadJson))
                    .contains("{\"event\":\"HAND_OFF_GONE\",\"from\":\"APPROVED\",\"guard\":\"NONE\","
                            + "\"to\":\"FAILED_NOT_CHARGED\"}");
        }
    }

    // Covers: 7.2 hand_off read not_sent or uncertain (NotSent, Uncertain, any ProtocolError but a 404, or Rejected:
    // the intent stays APPROVED with nothing written but the event, and the next pass hands off)
    @Test
    void aFailedRead_staysApproved_andTheNextPassHandsOff() {
        List<CallOutcome<CheckoutView>> failures = List.of(new CallOutcome.NotSent<>("down"),
                new CallOutcome.Uncertain<>("timeout"),
                new CallOutcome.ProtocolError<>(500, "internal_error", "", Optional.empty()),
                new CallOutcome.Rejected<>(400, List.of()));
        for (CallOutcome<CheckoutView> failure : failures) {
            Lab one = newLab();
            one.entries = lab.entries;
            RewritingGateway gateway = new RewritingGateway(one.merchant).rewrite(MerchantCall.GET, always(failure));
            Lab.Worker worker = one.worker("w", gateway);
            IntentId id = worker.orchestrator().propose(one.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
            one.drive(worker, id);
            one.decide(worker, id, true);
            long version = one.intent(id).version();

            worker.orchestrator().advance(id);

            assertThat(one.intent(id).state()).as(failure.toString()).isEqualTo(IntentState.APPROVED);
            assertThat(one.intent(id).version()).as("nothing written").isEqualTo(version);
            String event = failure instanceof CallOutcome.NotSent<?> ? "NOT_SENT" : "UNCERTAIN";
            assertThat(one.events(id).stream().filter(e -> e.type().equals("transition")).map(IntentEvent::payloadJson))
                    .contains("{\"event\":\"" + event + "\",\"from\":\"APPROVED\",\"guard\":\"NONE\",\"to\":\"APPROVED\"}");

            gateway.rewrite(MerchantCall.GET, withUrl(URL));
            assertThat(one.drive(worker, id)).isEqualTo(IntentState.HANDED_OFF);
        }
    }

    // Covers: 7.2 hand_off_refused, I12, I8 (no continue_url, or one off the shop's domain: FAILED_NOT_CHARGED with
    // continue_url_not_allowed, the session cancelled, and an event with the host only, none when absent)
    @Test
    void handOffRefused_failsNotCharged_andCancels() {
        record Case(Optional<String> url, String event) {
        }
        for (Case c : List.of(new Case(Optional.empty(), "{}"),
                new Case(Optional.of("https://evil.example/c?key=k-secret-1"), "{\"host\":\"evil.example\"}"),
                new Case(Optional.of("http://shop.example/c"), "{\"host\":\"shop.example\"}"),
                new Case(Optional.of("/cart/c/abc?key=k-secret-1"), "{}"))) {
            Lab one = newLab();
            one.entries = lab.entries;
            Lab.Worker worker = one.worker("w", new RewritingGateway(one.merchant).rewrite(MerchantCall.GET,
                    view(c.url(), v -> v)));
            IntentId id = worker.orchestrator().propose(one.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
            one.drive(worker, id);
            one.decide(worker, id, true);

            assertThat(one.drive(worker, id)).as(c.toString()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(one.intent(id).failureReason()).contains("continue_url_not_allowed");
            assertThat(one.intent(id).handoffUrl()).isEmpty();
            assertThat(one.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.CANCEL).hasSize(1);
            assertThat(one.merchant.session(one.intent(id).sessionId().orElseThrow()).orElseThrow().status())
                    .isEqualTo(CheckoutStatus.CANCELED);
            assertThat(one.events(id).stream().filter(e -> e.type().equals("hand_off_refused"))
                    .map(IntentEvent::payloadJson)).containsExactly(c.event());
            assertThat(one.events(id).stream().filter(e -> e.type().equals("transition")).map(IntentEvent::payloadJson))
                    .contains("{\"event\":\"HAND_OFF_REFUSED\",\"from\":\"APPROVED\",\"guard\":\"NONE\","
                            + "\"to\":\"FAILED_NOT_CHARGED\"}");
            assertThat(one.events(id)).noneMatch(e -> e.payloadJson().contains("k-secret-1"));
        }
    }

    // Covers: 7.2 hand_off_unexpected (completed or complete_in_progress: someone else acted; ESCALATED, nothing sent)
    @Test
    void handOffUnexpected_escalates() {
        for (CheckoutStatus s : List.of(CheckoutStatus.COMPLETED, CheckoutStatus.COMPLETE_IN_PROGRESS)) {
            Lab one = newLab();
            one.entries = lab.entries;
            Lab.Worker worker = one.worker("w", new RewritingGateway(one.merchant).rewrite(MerchantCall.GET,
                    view(Optional.of(URL), v -> status(v, s))));
            IntentId id = worker.orchestrator().propose(one.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
            one.drive(worker, id);
            one.decide(worker, id, true);

            assertThat(one.drive(worker, id)).as(s.name()).isEqualTo(IntentState.ESCALATED);
            assertThat(one.intent(id).failureReason()).contains("handoff_status_unexpected");
            assertThat(one.intents.attempts(id)).noneMatch(a -> a.op() == AttemptOp.CANCEL || a.op() == AttemptOp.COMPLETE);
            // 7.2: the escalation event lists the attempts for the human who looks (here the create).
            assertThat(one.events(id).stream().filter(e -> e.type().equals("escalated")).map(IntentEvent::payloadJson))
                    .singleElement().satisfies(e -> assertThat(e).contains("\"op\":\"CREATE\"", "\"status\":\"RESPONDED\""));
            assertThat(one.events(id).stream().filter(e -> e.type().equals("transition")).map(IntentEvent::payloadJson))
                    .contains("{\"event\":\"HAND_OFF_UNEXPECTED\",\"from\":\"APPROVED\",\"guard\":\"NONE\","
                            + "\"to\":\"ESCALATED\"}");
        }
    }

    // Covers: 7.2 quote_ready (handoff) (a status the buyer cannot finish from is no quote: a canceled answer takes the
    // neither-a-quote path, though it carries no message)
    @Test
    void quoteReady_atAHandoffMerchant_refusesACanceledAnswer() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE,
                as(CheckoutStatus.CANCELED, false)));
        IntentId id = worker.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();

        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).failureReason()).contains("quote_unsupported");
    }

    // Covers: 7.2 hand_off_changed, I2 (the currency, the item, or the number of lines differs while the total is the
    // approved one: each is a change, never a hand_off)
    @Test
    void handOffChanged_readsCurrencyLinesAndTheirCount_notOnlyTheTotal() {
        List<UnaryOperator<CheckoutView>> changes = List.of(
                v -> new CheckoutView(v.id(), v.status(), "EUR", v.lines(), v.subtotalMinor(), v.taxMinor(),
                        v.totalMinor(), v.order(), v.buyer(), v.messages(), v.fulfillment(), v.fulfillmentMinor(),
                        v.fulfillmentOffered(), v.continueUrl()),
                v -> withLines(v, v.lines().stream().map(l -> new LineView("item_999", l.quantity(),
                        l.unitPriceMinor(), l.lineId())).toList()),
                v -> withLines(v, List.of(v.lines().getFirst(), v.lines().getFirst())));
        for (UnaryOperator<CheckoutView> change : changes) {
            Lab one = newLab();
            one.entries = lab.entries;
            Lab.Worker worker = one.worker("w", new RewritingGateway(one.merchant).rewrite(MerchantCall.GET,
                    view(Optional.of(URL), change)));
            IntentId id = worker.orchestrator().propose(one.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
            one.drive(worker, id);
            one.decide(worker, id, true);

            worker.orchestrator().advance(id);

            assertThat(one.intent(id).state()).isEqualTo(IntentState.QUOTING);
            assertThat(one.intents.attempts(id)).anyMatch(a -> a.op() == AttemptOp.GET);
        }
    }

    // Covers: 7.2 hand_off_changed, I2 (the lines are counted: a GET showing [A, B, B] against an approved [A, A, B] is a
    // change though the number of lines and their set are the same; the approved lines in another order are not)
    @Test
    void handOffChanged_countsRepeatedLines() {
        List<dev.ucplab.core.intent.ProposedLine> basket = List.of(new dev.ucplab.core.intent.ProposedLine("item_123", 2),
                new dev.ucplab.core.intent.ProposedLine("item_456", 1));
        for (boolean changed : new boolean[] {true, false}) {
            Lab one = newLab();
            one.entries = lab.entries;
            Lab.Worker worker = one.worker("w", new RewritingGateway(one.merchant)
                    .rewrite(MerchantCall.CREATE, view(Optional.empty(), oneEach(0, 0, 1)))
                    .rewrite(MerchantCall.GET, view(Optional.of(URL), changed ? oneEach(0, 1, 1) : oneEach(0, 1, 0))));
            IntentId id = worker.orchestrator().propose(one.alice(), SHOP, basket, "buy").intentId();
            assertThat(one.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
            one.decide(worker, id, true);

            worker.orchestrator().advance(id);

            assertThat(one.intent(id).state()).as(changed ? "[A, B, B] against [A, A, B]" : "[A, B, A] against [A, A, B]")
                    .isEqualTo(changed ? IntentState.QUOTING : IntentState.HANDED_OFF);
        }
    }

    /**
     * The merchant's lines for item_123 and item_456 as lines of quantity 1 at their own prices, in {@code order}
     * (0 is item_123, 1 is item_456), with the view's total kept.
     */
    private static UnaryOperator<CheckoutView> oneEach(int... order) {
        return v -> {
            List<LineView> one = new java.util.ArrayList<>();
            for (String item : List.of("item_123", "item_456")) {
                LineView l = v.lines().stream().filter(x -> x.itemId().equals(item)).findFirst().orElseThrow();
                one.add(new LineView(l.itemId(), 1, l.unitPriceMinor(), l.lineId()));
            }
            return withLines(v, java.util.Arrays.stream(order).mapToObj(one::get).toList());
        };
    }

    /** The view with {@code lines} and its own total kept, so only the lines differ. */
    private static CheckoutView withLines(CheckoutView v, List<LineView> lines) {
        return new CheckoutView(v.id(), v.status(), v.currency(), lines, v.subtotalMinor(), v.taxMinor(), v.totalMinor(),
                v.order(), v.buyer(), v.messages(), v.fulfillment(), v.fulfillmentMinor(), v.fulfillmentOffered(),
                v.continueUrl());
    }

    // Covers: 7.2 hand_off (send_complete's order: an approval left PENDING by a crash inside decide is recovered to
    // APPROVED, as the owner's, then consumed), I10
    @Test
    void aPendingApprovalUnderAnApprovedIntent_isRecovered_thenConsumed_atHandOff() {
        CrashingApprovalRepository crashing = new CrashingApprovalRepository(lab.approvals);
        RewritingGateway gateway = new RewritingGateway(lab.merchant);
        gateway.rewrite(MerchantCall.GET, withUrl(URL));
        Lab.Worker dying = lab.worker("worker-x", gateway, crashing);
        IntentId id = dying.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        assertThat(lab.drive(dying, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        crashing.crashBeforeNext(CrashingApprovalRepository.Op.TRANSITION);
        assertThatThrownBy(() -> lab.decide(dying, id, true)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.PENDING);
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        assertThat(lab.drive(lab.worker("worker-y", gateway), id)).isEqualTo(IntentState.HANDED_OFF);

        dev.ucplab.core.approval.ApprovalRecord approval = lab.approvals.findLatestForSystem(id).orElseThrow();
        assertThat(approval.status()).isEqualTo(ApprovalStatus.CONSUMED);
        assertThat(approval.decidedBy()).contains(Lab.ALICE);
        assertThat(events(id, "approval_decision_recovered")).singleElement()
                .satisfies(e -> assertThat(e).contains("\"approval_id\":\"" + approval.id() + "\""));
        neverCompleted(id);
    }

    // ---------------------------------------------------------------- the handoff deadline (7.2 handoff_timeout, 7.6)

    /** The approval's persisted decision time. */
    private Instant decidedAt(IntentId id) {
        return lab.approvals.findLatestForSystem(id).orElseThrow().decidedAt().orElseThrow();
    }

    private static UnaryOperator<CallOutcome<CheckoutView>> uncertain() {
        return always(new CallOutcome.Uncertain<>("timeout"));
    }

    // Covers: 7.2 handoff_timeout, 7.6, I5 (a store whose reads keep failing keeps the intent APPROVED until 5 minutes
    // after the persisted decision, then the intent ends REJECTED with handoff_timeout and cancel_pending set in one
    // write; the owed cancel is then sent)
    @Test
    void aReadThatKeepsFailing_endsRejected_fiveMinutesAfterTheDecision() {
        RecordingGateway recording = new RecordingGateway(
                new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, uncertain()));
        Lab.Worker worker = lab.worker("w", recording);
        IntentId id = approved(worker);
        Instant decided = decidedAt(id);

        lab.clock.advance(Duration.between(lab.clock.instant(), decided.plus(FIVE_MINUTES).minusMillis(1)));
        worker.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);

        lab.clock.advance(Duration.ofMillis(1));
        int before = recording.calls().size();
        worker.orchestrator().advance(id);

        PurchaseIntent rejected = lab.intent(id);
        assertThat(rejected.state()).isEqualTo(IntentState.REJECTED);
        assertThat(rejected.failureReason()).contains("handoff_timeout");
        assertThat(rejected.cancelPending()).isTrue();
        assertThat(recording.calls().subList(before, recording.calls().size())).as("the timeout step sends nothing")
                .isEmpty();
        assertThat(transitions(id)).contains(
                "{\"event\":\"HANDOFF_TIMEOUT\",\"from\":\"APPROVED\",\"guard\":\"NONE\",\"to\":\"REJECTED\"}");
        lab.drive(worker, id);
        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.CANCEL).hasSize(1);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        neverCompleted(id);
    }

    // Covers: 7.2 handoff_timeout (checked at the start of the APPROVED step, before the GET: past the deadline no GET
    // is sent, even to a store that would hand off)
    @Test
    void theDeadline_isCheckedBeforeTheGet() {
        RecordingGateway recording = new RecordingGateway(
                new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, withUrl(URL)));
        Lab.Worker worker = lab.worker("w", recording);
        IntentId id = approved(worker);
        lab.clock.advance(FIVE_MINUTES);
        int before = recording.calls().size();

        worker.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(id).handoffUrl()).isEmpty();
        assertThat(recording.calls().subList(before, recording.calls().size())).as("no GET").isEmpty();
    }

    // Covers: 7.2 handoff_timeout, 7.6, S9 (a worker restart does not reset the deadline: it runs from the persisted
    // decision, not from when a worker took the intent over)
    @Test
    void aWorkerRestart_doesNotResetTheDeadline() {
        RewritingGateway failing = new RewritingGateway(lab.merchant);
        failing.rewrite(MerchantCall.GET, uncertain());
        Lab.Worker first = lab.worker("worker-a", failing);
        IntentId id = approved(first);
        Instant decided = decidedAt(id);
        lab.clock.advance(Duration.ofMinutes(3));
        first.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);

        // worker-a is gone; its lease runs out and worker-b takes the intent over.
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker second = lab.worker("worker-b", failing);
        second.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);

        lab.clock.advance(Duration.between(lab.clock.instant(), decided.plus(FIVE_MINUTES)));
        second.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(id).failureReason()).contains("handoff_timeout");
    }

    // Covers: 7.2 handoff_timeout, 7.6 (an approval left PENDING by a crash inside decide is first recovered, which
    // persists decided_at; its 5 minutes run from that write, and the recovery happens before the GET)
    @Test
    void aPendingApproval_isRecoveredFirst_andItsFiveMinutesRunFromThatWrite() {
        CrashingApprovalRepository crashing = new CrashingApprovalRepository(lab.approvals);
        RecordingGateway recording = new RecordingGateway(
                new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, uncertain()));
        Lab.Worker dying = lab.worker("worker-x", recording, crashing);
        IntentId id = dying.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        assertThat(lab.drive(dying, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        crashing.crashBeforeNext(CrashingApprovalRepository.Op.TRANSITION);
        assertThatThrownBy(() -> lab.decide(dying, id, true)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().decidedAt()).isEmpty();
        lab.clock.advance(Duration.ofMinutes(4));
        Instant recoveredAt = lab.clock.instant();
        Lab.Worker worker = lab.worker("worker-y", recording);

        worker.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(decidedAt(id)).isEqualTo(recoveredAt);
        assertThat(events(id, "approval_decision_recovered")).hasSize(1);
        lab.clock.advance(FIVE_MINUTES.minusMillis(1));
        worker.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
        lab.clock.advance(Duration.ofMillis(1));
        worker.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.REJECTED);
        assertThat(events(id, "approval_decision_recovered")).as("recovered once").hasSize(1);
    }

    // Covers: 7.2 approval_invalid, I3, I5, 9.2 (an APPROVED approval with no persisted decision time was not written
    // by ApprovalService: the step does not guess a deadline and does not throw, which would retry it every pass
    // forever; it escalates with approval_invalid, sends nothing and writes no attempt)
    @Test
    void anApprovalWithNoDecisionTime_isApprovalInvalid_andNothingIsSent() {
        CrashingApprovalRepository crashing = new CrashingApprovalRepository(lab.approvals);
        RecordingGateway recording = new RecordingGateway(
                new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, withUrl(URL)));
        Lab.Worker dying = lab.worker("worker-x", recording, crashing);
        IntentId id = dying.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        assertThat(lab.drive(dying, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        crashing.crashBeforeNext(CrashingApprovalRepository.Op.TRANSITION);
        assertThatThrownBy(() -> lab.decide(dying, id, true)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        // A forged decision: PENDING to APPROVED with no decider, so no decision time is kept.
        var pending = lab.approvals.findLatestForSystem(id).orElseThrow();
        lab.approvals.transition(pending.id(), ApprovalStatus.PENDING, ApprovalStatus.APPROVED, lab.clock.instant(),
                Optional.empty());
        assertThat(lab.approvals.findLatestForSystem(id).orElseThrow().decidedAt()).isEmpty();
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker worker = lab.worker("worker-y", recording);
        int before = recording.calls().size();

        int attempts = lab.intents.attempts(id).size();

        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("approval_invalid");
        assertThat(recording.calls().subList(before, recording.calls().size())).as("no GET").isEmpty();
        assertThat(lab.intents.attempts(id)).hasSize(attempts);
        assertThat(lab.events(id)).anyMatch(e -> e.type().equals("approval_invalid"));
    }

    // Covers: 7.2 approval_invalid, I5, 9.2 (a PENDING approval whose recovery write is lost, so it still has no
    // decision time: approval_invalid before the deadline and the GET, never a throw)
    @Test
    void aPendingRecoveryThatLeavesNoDecisionTime_isApprovalInvalid_andNothingIsSent() {
        CrashingApprovalRepository crashing = new CrashingApprovalRepository(lab.approvals);
        RecordingGateway recording = new RecordingGateway(
                new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, withUrl(URL)));
        Lab.Worker dying = lab.worker("worker-x", recording, crashing);
        IntentId id = dying.orchestrator().propose(lab.alice(), SHOP, TWO_SHIRTS, "buy").intentId();
        assertThat(lab.drive(dying, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        crashing.crashBeforeNext(CrashingApprovalRepository.Op.TRANSITION);
        assertThatThrownBy(() -> lab.decide(dying, id, true)).isInstanceOf(InterceptingGateway.SimulatedCrash.class);
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        Lab.Worker worker = lab.worker("worker-y", recording, new LostTransitions(lab.approvals));
        int before = recording.calls().size();

        assertThat(worker.orchestrator().advance(id).after()).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("approval_invalid");
        assertThat(recording.calls().subList(before, recording.calls().size())).as("no GET").isEmpty();
    }

    /** An approval store whose transitions are lost: each answers false and writes nothing (another writer won). */
    private record LostTransitions(dev.ucplab.core.approval.ApprovalRepository delegate)
            implements dev.ucplab.core.approval.ApprovalRepository {
        @Override
        public void insert(dev.ucplab.core.approval.ApprovalRecord approval) {
            delegate.insert(approval);
        }

        @Override
        public Optional<dev.ucplab.core.approval.ApprovalRecord> findLatest(dev.ucplab.core.identity.UserId owner,
                IntentId intentId) {
            return delegate.findLatest(owner, intentId);
        }

        @Override
        public Optional<dev.ucplab.core.approval.ApprovalRecord> findLatestForSystem(IntentId intentId) {
            return delegate.findLatestForSystem(intentId);
        }

        @Override
        public List<dev.ucplab.core.approval.ApprovalRecord> listForSystem(IntentId intentId) {
            return delegate.listForSystem(intentId);
        }

        @Override
        public boolean transition(String approvalId, ApprovalStatus expected, ApprovalStatus next, Instant at,
                Optional<dev.ucplab.core.identity.UserId> decidedBy) {
            return false;
        }
    }

    // Covers: 7.2 handoff_timeout (a re-quote's new approval starts a new 5 minutes)
    @Test
    void aRequotesNewApproval_startsANewFiveMinutes() {
        java.util.concurrent.atomic.AtomicInteger gets = new java.util.concurrent.atomic.AtomicInteger();
        UnaryOperator<CallOutcome<CheckoutView>> changedOnce = view(Optional.of(URL), v -> total(v, v.totalMinor() + 1));
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET,
                o -> gets.getAndIncrement() == 0 ? changedOnce.apply(o) : new CallOutcome.Uncertain<>("timeout")));
        IntentId id = approved(worker);
        Instant first = decidedAt(id);
        lab.clock.advance(Duration.ofMinutes(1));
        worker.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.QUOTING);
        assertThat(lab.drive(worker, id)).isEqualTo(IntentState.AWAITING_APPROVAL);
        lab.clock.advance(Duration.ofMinutes(2));
        lab.decide(worker, id, true);
        Instant second = decidedAt(id);
        assertThat(second).isAfter(first);

        lab.clock.advance(Duration.between(lab.clock.instant(), first.plus(FIVE_MINUTES).plusSeconds(1)));
        worker.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).as("5 minutes from the first approval do not apply").isEqualTo(
                IntentState.APPROVED);
        lab.clock.advance(Duration.between(lab.clock.instant(), second.plus(FIVE_MINUTES)));
        worker.orchestrator().advance(id);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(id).failureReason()).contains("handoff_timeout");
    }

    /** Runs recovery passes, one worker period apart, until {@code id} is terminal or {@code passes} have run. */
    private void recoverUntilTerminal(Lab.Worker worker, IntentId id, int passes) {
        dev.ucplab.core.recovery.RecoveryConfig config = dev.ucplab.core.recovery.RecoveryConfig.defaults();
        dev.ucplab.core.recovery.RecoveryWorker recovery = new dev.ucplab.core.recovery.RecoveryWorker(lab.intents,
                lab.scan, worker.orchestrator(), lab.clock, lab.clock::advance, Runnable::run, config, worker.id());
        for (int i = 0; i < passes && !lab.intent(id).state().isTerminal(); i++) {
            recovery.runOnce();
            lab.clock.advance(config.period());
        }
    }

    // Covers: 7.6 (the recovery worker picks up an APPROVED intent at a handoff merchant and drives it: the GET of the
    // hand_off rows, never a complete)
    @Test
    void theRecoveryWorker_drivesApprovedAtAHandoffMerchant() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, withUrl(URL)));
        IntentId id = approved(worker);

        recoverUntilTerminal(worker, id, 5);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.HANDED_OFF);
        neverCompleted(id);
    }

    // Covers: 7.6, 7.2 handoff_timeout, I5 (the recovery worker enforces the deadline: with reads that keep failing,
    // its passes end the intent REJECTED with handoff_timeout within one pass of the 5 minutes, then send the owed cancel)
    @Test
    void theRecoveryWorker_enforcesTheDeadline() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, uncertain()));
        IntentId id = approved(worker);
        Instant decided = decidedAt(id);

        recoverUntilTerminal(worker, id, 200);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(id).failureReason()).contains("handoff_timeout");
        assertThat(lab.clock.instant()).isBefore(decided.plus(FIVE_MINUTES).plus(
                dev.ucplab.core.recovery.RecoveryConfig.defaults().period().multipliedBy(2)));
        recoverUntilTerminal(worker, id, 5);
        lab.clock.advance(Duration.ofSeconds(5));
        new dev.ucplab.core.recovery.RecoveryWorker(lab.intents, lab.scan, worker.orchestrator(), lab.clock,
                lab.clock::advance, Runnable::run, dev.ucplab.core.recovery.RecoveryConfig.defaults(), worker.id())
                .runOnce();
        assertThat(lab.intents.attempts(id)).filteredOn(a -> a.op() == AttemptOp.CANCEL).hasSize(1);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        neverCompleted(id);
    }

    // Covers: 7.1 (HANDED_OFF is terminal: a later pass does nothing)
    @Test
    void handedOff_isTerminal() {
        Lab.Worker worker = lab.worker("w", new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, withUrl(URL)));
        IntentId id = approved(worker);
        lab.drive(worker, id);
        long version = lab.intent(id).version();

        assertThat(worker.orchestrator().advance(id).idle()).isTrue();
        assertThat(lab.intent(id).version()).isEqualTo(version);
        assertThat(IntentState.HANDED_OFF.isTerminal()).isTrue();
    }
}
