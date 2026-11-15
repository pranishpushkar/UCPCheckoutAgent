package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.ALICE;
import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static dev.ucplab.core.orchestrator.RewritingGateway.as;
import static dev.ucplab.core.orchestrator.RewritingGateway.error;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ucplab.core.approval.ApprovalDecision;
import dev.ucplab.core.approval.ApprovalStatus;
import dev.ucplab.core.intent.AttemptId;
import dev.ucplab.core.intent.AttemptOp;
import dev.ucplab.core.intent.AttemptStatus;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentNotFoundException;
import dev.ucplab.core.intent.IntentRepository;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.MerchantAttempt;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CallOutcome;
import dev.ucplab.core.merchant.CheckoutStatus;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.CheckoutWire;
import dev.ucplab.core.merchant.FrozenBody;
import dev.ucplab.core.merchant.FulfillmentView;
import dev.ucplab.core.merchant.IdempotencyKey;
import dev.ucplab.core.merchant.LineView;
import dev.ucplab.core.merchant.MutatingOp;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.fake.Fault;
import dev.ucplab.core.merchant.fake.FaultRule;
import dev.ucplab.core.merchant.fake.MerchantCall;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The second round of the H3 P7 mutation pass (7.2, 7.4, 7.5, 7.6, 9.2, I1, I3, I4): the corrupt or unusual rows a step must
 * refuse or handle (an intent that does not exist, one still PROPOSED, stored answers that are not a usable quote), the owed
 * cancel and the orphan probe stopping on the try that is the last and not one later, and what an escalation records.
 */
class OrchestratorSecondRoundMutationTest {

    private Lab lab = new Lab();
    private Lab.Worker w = lab.worker("worker-a");

    private List<String> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).map(IntentEvent::payloadJson).toList();
    }

    private List<MerchantAttempt> attempts(IntentId id, AttemptOp op) {
        return lab.intents.attempts(id).stream().filter(a -> a.op() == op).toList();
    }

    private long clientCalls(MerchantCall call) {
        return lab.merchant.log().stream().filter(c -> c.call() == call && !c.delivered()).count();
    }

    // Covers: 7.3, 7.7. Kills DefaultOrchestrator.propose:262 RemoveConditional_EQUAL_ELSE: a proposal with no line is refused by the
    // orchestrator itself, with its own message (an intent would also refuse it, with another)
    @Test
    void aProposalWithNoLine_isRefusedWithTheProposalsOwnMessage() {
        assertThatThrownBy(() -> w.orchestrator().propose(lab.alice(), SHOP, List.of(), "nothing"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("a proposal needs at least one line");
    }

    // Covers: 7.3. Kills DefaultOrchestrator.lambda$step$8:369 NullReturnVals (NO_COVERAGE): an advance of an intent that does not
    // exist is reported as not found
    @Test
    void advancingAnUnknownIntent_isNotFound() {
        assertThatThrownBy(() -> w.orchestrator().advance(new IntentId("nobody"))).isInstanceOf(IntentNotFoundException.class);
    }

    // Covers: 9.1, 7.2. Kills DefaultOrchestrator.applyProposalPolicy:389 to :396 (NO_COVERAGE): an intent found still PROPOSED has
    // the proposal policy applied by the next step: allowed it moves to QUOTING, denied it is REJECTED with the rule as its
    // reason; the decision and the transition are recorded
    @Test
    void anIntentFoundStillProposed_hasItsPolicyApplied() {
        IntentId ok = new IntentId("proposed-ok");
        lab.intents.insert(PurchaseIntent.proposed(ok, ALICE, SHOP, "k-ok", TWO_SHIRTS, "alice@example.com", lab.clock.instant()));
        w.orchestrator().advance(ok);
        assertThat(lab.intent(ok).state()).isEqualTo(IntentState.QUOTING);
        assertThat(lab.intent(ok).failureReason()).as("an allowed proposal has no failure reason").isEmpty();
        assertThat(events(ok, "policy_decision")).anySatisfy(e -> assertThat(e).contains("\"stage\":\"proposal\"").contains("\"allowed\":true"));
        assertThat(events(ok, "transition")).anySatisfy(e -> assertThat(e).contains("\"from\":\"PROPOSED\"").contains("\"event\":\"POLICY_OK\""));

        IntentId no = new IntentId("proposed-no");
        List<ProposedLine> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(new ProposedLine("item_" + i, 1));
        }
        lab.intents.insert(PurchaseIntent.proposed(no, ALICE, SHOP, "k-no", six, "alice@example.com", lab.clock.instant()));
        w.orchestrator().advance(no);
        assertThat(lab.intent(no).state()).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(no).failureReason()).contains("policy_P4");
        assertThat(events(no, "policy_decision")).anySatisfy(e -> assertThat(e).contains("\"allowed\":false").contains("\"rule\":\"P4\""));
        assertThat(events(no, "transition")).anySatisfy(e -> assertThat(e).contains("\"event\":\"POLICY_DENIED\""));
        assertThat(lab.merchant.log()).as("applying the policy is the whole step: no merchant call").isEmpty();
    }

    // ---------------------------------------------------------------- stored answers that are not a usable quote

    private CheckoutView viewOf(CheckoutStatus status, boolean offered, Optional<FulfillmentView> fulfillment,
            List<dev.ucplab.core.merchant.MerchantMessage> messages, List<LineView> lines) {
        return new CheckoutView(new SessionId("cs_unknown"), status, "USD", lines, 5000, 400, 5400, Optional.empty(),
                Optional.of(dev.ucplab.core.merchant.Buyer.ofEmail("alice@example.com")), messages, fulfillment, 0, offered);
    }

    private static final List<LineView> LINES = List.of(new LineView("item_123", 2, 2500, Optional.of("li_1")));

    private IntentId quotingWithStoredAnswer(String name, String summary) {
        IntentId id = new IntentId(name);
        lab.intents.insert(PurchaseIntent.proposed(id, ALICE, SHOP, "dedupe-" + name, TWO_SHIRTS, "alice@example.com",
                lab.clock.instant()).withState(IntentState.QUOTING, lab.clock.instant())
                .withSessionId(new SessionId("cs_unknown")).withLease(Optional.of("setup"), Optional.of(lab.clock.instant())));
        MerchantAttempt answered = new MerchantAttempt(new AttemptId(name + "-a"), id, AttemptOp.CREATE, 0,
                Optional.of(IdempotencyKey.derive(id, MutatingOp.CREATE, 0)), Optional.empty(), Optional.of(new FrozenBody("{}")),
                AttemptStatus.RESPONDED, Optional.of(201), Optional.of(summary), lab.clock.instant(), Optional.of(lab.clock.instant()));
        lab.intents.recordAttempt(answered, lab.intent(id), 0, "setup");
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        return id;
    }

    // Covers: 7.2 shipping selection, 8.4. Kills DefaultOrchestrator.pendingSelection:523 (all three), :528 and :531 (all three):
    // a stored answer that is not an incomplete one with no message, with usable lines, from a merchant that offers
    // fulfillment, never starts a selection step: the quote cycle that already called ends exhausted and nothing is sent
    @Test
    void aStoredAnswerThatIsNotASelectionRequest_isNeverAnsweredWithAnUpdate() {
        FulfillmentView unselected = new FulfillmentView(List.of(new FulfillmentView.Method("method_1", "shipping", List.of("li_1"),
                List.of(new FulfillmentView.Destination("addr_1", Optional.of("US"))), Optional.empty(), List.of())));
        List<String> summaries = List.of(
                CheckoutWire.responseSummary(viewOf(CheckoutStatus.READY_FOR_COMPLETE, true, Optional.of(unselected), List.of(), LINES)),
                CheckoutWire.responseSummary(viewOf(CheckoutStatus.INCOMPLETE, true, Optional.of(unselected), List.of(error("x")), LINES)),
                CheckoutWire.responseSummary(viewOf(CheckoutStatus.INCOMPLETE, false, Optional.empty(), List.of(), LINES)),
                "{\"status\":\"incomplete\",\"message_codes\":[],\"fulfillment_offered\":true}",
                "{\"status\":\"incomplete\",\"fulfillment_offered\":true,\"lines\":[]}");
        int n = 0;
        for (String summary : summaries) {
            lab = new Lab();
            w = lab.worker("worker-a");
            IntentId id = quotingWithStoredAnswer("stored-" + n++, summary);

            w.orchestrator().advance(id);

            assertThat(lab.intent(id).state()).as(summary).isEqualTo(IntentState.FAILED_NOT_CHARGED);
            assertThat(lab.intent(id).failureReason()).as(summary).contains("quote_retries_exhausted");
            assertThat(clientCalls(MerchantCall.UPDATE)).as(summary).isZero();
        }
    }

    // Covers: 8.4. Kills DefaultOrchestrator.lastQuoteShape:1342 RemoveConditional_ORDER_IF: an update built when no stored answer
    // has a usable shape carries no line ids, and is still sent
    @Test
    void anUpdateWithNoStoredShape_isStillSent() {
        IntentId id = new IntentId("noshape");
        lab.intents.insert(PurchaseIntent.proposed(id, ALICE, SHOP, "dedupe-noshape", TWO_SHIRTS, "alice@example.com", lab.clock.instant())
                .withState(IntentState.QUOTING, lab.clock.instant()).withRequoteCount(1).withSessionId(new SessionId("cs_unknown")));

        w.orchestrator().advance(id);

        assertThat(attempts(id, AttemptOp.UPDATE)).singleElement().satisfies(a -> assertThat(a.requestBody().orElseThrow().json())
                .doesNotContain("\"id\":\"li_"));
        assertThat(lab.intent(id).failureReason()).contains("merchant_error_not_found");
    }

    // ---------------------------------------------------------------- selection and fulfillment

    private static java.util.function.UnaryOperator<CallOutcome<CheckoutView>> view(
            java.util.function.UnaryOperator<CheckoutView> change) {
        return outcome -> {
            if (!(outcome instanceof CallOutcome.Ok<CheckoutView> ok)) {
                return outcome;
            }
            CheckoutView v = change.apply(ok.value());
            return new CallOutcome.Ok<>(ok.httpStatus(), v, v.messages());
        };
    }

    private IntentId driveQuote(Lab l, Lab.Worker worker) {
        l.merchant.enableFulfillment();
        IntentId id = l.propose(worker, TWO_SHIRTS).intentId();
        for (int i = 0; i < 12 && l.intent(id).state() == IntentState.QUOTING; i++) {
            worker.orchestrator().advance(id);
        }
        return id;
    }

    // Covers: 9.2, 7.2. Kills DefaultOrchestrator.lambda$unsupportedFulfillment$16:539 RemoveConditional_ORDER_IF (the more-than-one-
    // method operand): an answer that lists two methods is not supported either: the quote ends exhausted and says why
    @Test
    void moreThanOneMethod_isUnsupported() {
        lab = new Lab();
        w = lab.worker("worker-a", new RewritingGateway(lab.merchant).rewrite(MerchantCall.UPDATE, view(v -> {
            FulfillmentView f = v.fulfillment().orElseThrow();
            FulfillmentView.Method m = f.methods().get(0);
            FulfillmentView.Method second = new FulfillmentView.Method("method_2", "pickup", m.lineItemIds(), m.destinations(),
                    Optional.empty(), List.of());
            return new CheckoutView(v.id(), v.status(), v.currency(), v.lines(), v.subtotalMinor(), v.taxMinor(), v.totalMinor(),
                    v.order(), v.buyer(), v.messages(), Optional.of(new FulfillmentView(List.of(m, second))), v.fulfillmentMinor(), true);
        })));

        IntentId id = driveQuote(lab, w);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(events(id, "fulfillment_unsupported")).hasSize(1);
    }

    // Covers: 7.2 shipping selection. Kills DefaultOrchestrator.selectionPending:546 RemoveConditional_EQUAL_IF (an answer that
    // shows a fulfillment part is a selection request even when its flag says the profile did not list the capability) and
    // :549 BooleanTrueReturnVals / onQuoteOutcome:628 RemoveConditional_EQUAL_IF (the status operand): an incomplete answer with
    // nothing left to select asks for no selection step, and a canceled one is never a selection request
    @Test
    void aSelectionIsRequested_onlyByAnIncompleteAnswerWithSomethingLeftToSelect() {
        lab = new Lab();
        w = lab.worker("worker-a", new RewritingGateway(lab.merchant).rewrite(MerchantCall.UPDATE, view(v -> v.withFulfillmentOffered(false))));
        IntentId shown = driveQuote(lab, w);
        assertThat(lab.intent(shown).state()).as("the fulfillment part is there, so the selection goes on").isEqualTo(IntentState.AWAITING_APPROVAL);

        lab = new Lab();
        w = lab.worker("worker-a", new RewritingGateway(lab.merchant).rewrite(MerchantCall.UPDATE, as(CheckoutStatus.INCOMPLETE, false)));
        IntentId nothingLeft = driveQuote(lab, w);
        assertThat(lab.intent(nothingLeft).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(events(nothingLeft, "fulfillment_selection_needed")).as("the create and the first update ask, the last answer has nothing left").hasSize(3);

        lab = new Lab();
        w = lab.worker("worker-a", new RewritingGateway(lab.merchant).rewrite(MerchantCall.CREATE, as(CheckoutStatus.CANCELED, false)));
        IntentId canceled = driveQuote(lab, w);
        assertThat(lab.intent(canceled).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(events(canceled, "fulfillment_selection_needed")).isEmpty();
    }

    // ---------------------------------------------------------------- reconciliation of rows that are not usual

    private IntentId reconcilingWithAnAttempt(AttemptStatus status, Duration sentAgo, CheckoutStatus read) {
        lab = new Lab();
        RewritingGateway merchant = new RewritingGateway(lab.merchant).rewrite(MerchantCall.GET, as(read, false));
        w = lab.worker("worker-r", merchant);
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        PurchaseIntent claimed = lab.intents.claim(id, "worker-r", lab.config.lease()).orElseThrow();
        PurchaseIntent reconciling = claimed.withState(IntentState.RECONCILING, lab.clock.instant())
                .withUncertainSince(lab.clock.instant().minus(sentAgo));
        MerchantAttempt attempt = new MerchantAttempt(new AttemptId("c-1"), id, AttemptOp.COMPLETE, 0,
                Optional.of(IdempotencyKey.derive(id, MutatingOp.COMPLETE, 0)), Optional.empty(),
                Optional.of(new FrozenBody("{\"payment\":{}}")), status, Optional.empty(), Optional.empty(),
                lab.clock.instant().minus(sentAgo), Optional.empty());
        lab.intents.recordAttempt(attempt, reconciling, claimed.version(), "worker-r");
        return id;
    }

    // Covers: 7.5, 7.2. Kills DefaultOrchestrator.lambda$reconcile$32:1040 BooleanTrueReturnVals / RemoveConditional_EQUAL_IF and
    // :1041 RemoveConditional_EQUAL_IF: an incomplete read with every attempt already answered does not wait for the quiet
    // period: the re-quote begins at once
    @Test
    void anIncompleteRead_withEveryAttemptAnswered_requotesAtOnce() {
        IntentId id = reconcilingWithAnAttempt(AttemptStatus.RESPONDED, Duration.ofSeconds(1), CheckoutStatus.INCOMPLETE);

        w.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.QUOTING);
        assertThat(lab.intent(id).requoteCount()).isEqualTo(1);
        assertThat(events(id, "reconcile_wait")).isEmpty();
    }

    // Covers: 7.5, 8.1. Kills DefaultOrchestrator.lambda$reconcile$31:1035 NullReturnVals (NO_COVERAGE): approving a resend reads
    // the intent back to learn the version it stored; an intent that is gone at that moment is reported as not found
    @Test
    void approvingAResend_ofAnIntentThatVanished_isNotFound() {
        IntentId id = reconcilingWithAnAttempt(AttemptStatus.UNCERTAIN, Duration.ofSeconds(30), CheckoutStatus.READY_FOR_COMPLETE);
        IntentRepository vanishing = (IntentRepository) Proxy.newProxyInstance(IntentRepository.class.getClassLoader(),
                new Class<?>[] {IntentRepository.class}, (proxy, m, args) -> {
                    if (m.getName().equals("findForSystem")) {
                        return Optional.empty();
                    }
                    try {
                        return m.invoke(lab.intents, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        Lab.Worker blind = lab.worker("worker-r", lab.merchant, lab.approvals, vanishing);    // the lease holder

        assertThatThrownBy(() -> blind.orchestrator().advance(id)).isInstanceOf(IntentNotFoundException.class);
    }

    // Covers: 9.2, I3. Kills DefaultOrchestrator.escalateInvalidApproval:1079 RemoveConditional_EQUAL_IF (both operands): the
    // "hash_matches" fact is false for a hash that differs, and false when there is no approval at all, even when the intent has
    // no approved hash either (two empty values are not a match)
    @Test
    void theHashMatchesFact_isFalseForADifferentHash_andForNoApprovalAtAll() {
        lab = new Lab();
        w = lab.worker("worker-h");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.decide(w, id, true)).isInstanceOf(ApprovalDecision.Approved.class);
        PurchaseIntent claimed = lab.intents.claim(id, "worker-h", lab.config.lease()).orElseThrow();
        lab.intents.update(claimed.withApprovedSnapshotHash(Optional.of("not-the-approved-hash")), claimed.version(), "worker-h");
        w.orchestrator().advance(id);
        assertThat(events(id, "approval_invalid")).singleElement().satisfies(e -> assertThat(e)
                .contains("\"approval_status\":\"APPROVED\"").contains("\"hash_matches\":false"));

        lab = new Lab();
        w = lab.worker("worker-h");
        IntentId bare = new IntentId("bare");
        lab.intents.insert(PurchaseIntent.proposed(bare, ALICE, SHOP, "bare-key", TWO_SHIRTS, "alice@example.com", lab.clock.instant())
                .withState(IntentState.APPROVED, lab.clock.instant()).withSessionId(new SessionId("cs_x")));
        w.orchestrator().advance(bare);
        assertThat(events(bare, "approval_invalid")).singleElement().satisfies(e -> assertThat(e)
                .contains("\"approval_status\":\"NONE\"").contains("\"hash_matches\":false"));
    }

    // ---------------------------------------------------------------- the last try

    private IntentId rejected() {
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, false);
        return id;
    }

    // Covers: 7.6. Kills DefaultOrchestrator.cancelIfOwed:1177 ConditionalsBoundary, Math, RemoveConditional_EQUAL_ELSE,
    // EQUAL_IF and ORDER_ELSE: the third unanswered cancel is the last: the very step that sends it gives up (the flag is
    // cleared and the event recorded in that step), and the step after it is idle
    @Test
    void theThirdCancel_givesUpInItsOwnStep() {
        lab.merchant.addRule(FaultRule.every(MerchantCall.CANCEL, new Fault.NotSent()));
        IntentId id = rejected();

        w.orchestrator().advance(id);
        w.orchestrator().advance(id);
        assertThat(lab.intent(id).cancelPending()).as("after the second").isTrue();
        assertThat(events(id, "cancel_gave_up")).isEmpty();
        w.orchestrator().advance(id);

        assertThat(lab.intent(id).cancelPending()).as("after the third").isFalse();
        assertThat(events(id, "cancel_gave_up")).singleElement().satisfies(e -> assertThat(e).contains("\"attempts\":3"));
        assertThat(w.orchestrator().advance(id).idle()).isTrue();
        assertThat(clientCalls(MerchantCall.CANCEL)).isEqualTo(3);
    }

    // Covers: 7.6. Kills DefaultOrchestrator.cancelIfOwed:1177 RemoveConditional_EQUAL_IF (the answered operand): a third cancel
    // that is answered did not give up: it clears the flag with no "gave up" event
    @Test
    void aThirdCancelThatIsAnswered_doesNotGiveUp() {
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CANCEL, 1, new Fault.NotSent()));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CANCEL, 2, new Fault.NotSent()));
        IntentId id = rejected();

        w.orchestrator().advance(id);
        w.orchestrator().advance(id);
        w.orchestrator().advance(id);

        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(attempts(id, AttemptOp.CANCEL)).extracting(MerchantAttempt::status)
                .containsExactly(AttemptStatus.UNCERTAIN, AttemptStatus.UNCERTAIN, AttemptStatus.RESPONDED);
        assertThat(events(id, "cancel_gave_up")).isEmpty();
    }

    // Covers: 7.6. Kills DefaultOrchestrator.probeOrphanSession:1255 ConditionalsBoundary, Math and RemoveConditional_ORDER_ELSE and
    // :1252 (the status of a probe's protocol error is stored): the third probe is the last: the step that sends it gives up;
    // a probe answered with a protocol error stores its status
    @Test
    void theThirdProbe_givesUpInItsOwnStep() {
        lab.merchant.addRule(FaultRule.every(MerchantCall.CREATE, new Fault.CommitThenDrop()));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();

        w.orchestrator().advance(id);
        w.orchestrator().advance(id);
        assertThat(lab.intent(id).cancelPending()).as("after the second probe").isTrue();
        assertThat(events(id, "orphan_probe_gave_up")).isEmpty();
        w.orchestrator().advance(id);

        assertThat(lab.intent(id).cancelPending()).as("after the third probe").isFalse();
        assertThat(events(id, "orphan_probe_gave_up")).singleElement().satisfies(e -> assertThat(e).contains("\"probes\":3"));
        assertThat(clientCalls(MerchantCall.CREATE)).isEqualTo(4);

        lab = new Lab();
        w = lab.worker("worker-a");
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 1, new Fault.CommitThenDrop()));
        lab.merchant.addRule(FaultRule.nth(MerchantCall.CREATE, 2, new Fault.Reject(409, "conflict", Optional.empty())));
        IntentId refused = lab.propose(w, TWO_SHIRTS).intentId();
        w.orchestrator().advance(refused);
        assertThat(attempts(refused, AttemptOp.CREATE).get(1).httpStatus()).contains(409);
    }

    // Covers: 7.6 M3 note. Kills DefaultOrchestrator.lambda$probeOrphanSession$39:1208 RemoveConditional_EQUAL_IF (the revision
    // operand): a create attempt of another revision is not the intent's create: with none of revision 0 nothing is probed
    @Test
    void aCreateOfAnotherRevision_isNotTheIntentsCreate() {
        IntentId id = new IntentId("rev1");
        lab.intents.insert(PurchaseIntent.proposed(id, ALICE, SHOP, "dedupe-rev1", TWO_SHIRTS, "alice@example.com", lab.clock.instant())
                .withState(IntentState.FAILED_NOT_CHARGED, lab.clock.instant()).withCancelPending(true)
                .withLease(Optional.of("setup"), Optional.of(lab.clock.instant())));
        MerchantAttempt other = new MerchantAttempt(new AttemptId("c-r1"), id, AttemptOp.CREATE, 1, Optional.empty(), Optional.empty(),
                Optional.empty(), AttemptStatus.UNCERTAIN, Optional.empty(), Optional.empty(), lab.clock.instant(), Optional.empty());
        lab.intents.recordAttempt(other, lab.intent(id), 0, "setup");
        lab.clock.advance(lab.config.lease().plusSeconds(1));

        w.orchestrator().advance(id);

        assertThat(events(id, "orphan_probe_no_create")).hasSize(1);
        assertThat(lab.merchant.log()).isEmpty();
    }
}
