package dev.ucplab.core.orchestrator;

import static dev.ucplab.core.orchestrator.Lab.ALICE;
import static dev.ucplab.core.orchestrator.Lab.SHOP;
import static dev.ucplab.core.orchestrator.Lab.TWO_SHIRTS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ucplab.core.identity.UserId;
import dev.ucplab.core.intent.Budget;
import dev.ucplab.core.intent.IntentEvent;
import dev.ucplab.core.intent.IntentId;
import dev.ucplab.core.intent.IntentState;
import dev.ucplab.core.intent.ProposalResult;
import dev.ucplab.core.intent.ProposedLine;
import dev.ucplab.core.intent.PurchaseIntent;
import dev.ucplab.core.merchant.CheckoutView;
import dev.ucplab.core.merchant.Completion;
import dev.ucplab.core.merchant.MerchantEntry;
import dev.ucplab.core.merchant.MerchantId;
import dev.ucplab.core.merchant.MerchantStatus;
import dev.ucplab.core.merchant.SessionId;
import dev.ucplab.core.merchant.Transport;
import dev.ucplab.core.merchant.fake.CallRecord;
import dev.ucplab.core.merchant.fake.MerchantCall;
import dev.ucplab.core.policy.PolicyDecision;
import dev.ucplab.core.policy.PolicyEngine;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * I12 in the orchestrator (4.3, 7.2, 7.6, 26.5; H5 C2 ★): an entry that is not owned is denied at propose before the
 * PolicyEngine, with rule id I12; at every later step an entry that changed under the intent, or one that fails I12 at a
 * step that would send, takes the intent to ESCALATED with nothing sent and no attempt written; an owed cancel at such a
 * merchant is not sent. The entries are on a non-loopback host, as a real store's would be.
 */
class OrchestratorOwnStoreTest {

    private final Lab lab = new Lab();

    private static MerchantEntry entry(boolean owned, Completion completion) {
        return new MerchantEntry(SHOP, URI.create("https://shop.example"), "Shop", MerchantStatus.ACTIVE,
                Transport.REST, completion, owned, Optional.empty());
    }

    private static final MerchantEntry OWNED = entry(true, Completion.AGENT);
    private static final MerchantEntry UNOWNED = entry(false, Completion.AGENT);
    /** Owned still, but another fingerprint: the file changed completion under the intent. */
    private static final MerchantEntry CHANGED = entry(true, Completion.HANDOFF);

    /** A PolicyEngine that must not be asked: I12 is checked before it. */
    private static final PolicyEngine NEVER = new PolicyEngine() {
        @Override
        public PolicyDecision evaluateProposal(UserId user, MerchantId merchant, List<ProposedLine> lines) {
            throw new AssertionError("the PolicyEngine was asked");
        }

        @Override
        public PolicyDecision evaluateQuote(UserId user, CheckoutView quote, Optional<Budget> budget) {
            throw new AssertionError("the PolicyEngine was asked");
        }
    };

    private void entryIs(Optional<MerchantEntry> entry) {
        lab.entries = id -> id.equals(SHOP) ? entry : Optional.empty();
    }

    /** The calls the agent sent (the fake logs a delivery as its own record). */
    private List<CallRecord> calls() {
        return lab.merchant.log().stream().filter(c -> !c.delivered()).toList();
    }

    /** A restart: the first worker's lease runs out, and a new worker takes the intent. */
    private Lab.Worker restarted() {
        lab.clock.advance(lab.config.lease().plusSeconds(1));
        return lab.worker("worker-b");
    }

    private List<IntentEvent> events(IntentId id, String type) {
        return lab.events(id).stream().filter(e -> e.type().equals(type)).toList();
    }

    /** An intent stored directly in {@code state} under {@code fingerprint}, with a session once past QUOTING. */
    private IntentId stored(String id, IntentState state, String fingerprint, boolean cancelPending,
                            Optional<SessionId> session) {
        PurchaseIntent intent = PurchaseIntent.proposed(new IntentId(id), ALICE, SHOP, fingerprint, "d-" + id, TWO_SHIRTS,
                "alice@example.com", lab.clock.instant()).withState(state, lab.clock.instant())
                .withCancelPending(cancelPending);
        lab.intents.insert(session.map(intent::withSessionId).orElse(intent));
        return intent.id();
    }

    private void assertEscalated(IntentId id, String reason, IntentState from) {
        PurchaseIntent after = lab.intent(id);
        assertThat(after.state()).as(from.name()).isEqualTo(IntentState.ESCALATED);
        assertThat(after.failureReason()).as(from.name()).contains(reason);
        assertThat(calls()).as("nothing sent from " + from).isEmpty();
        assertThat(lab.intents.attempts(id)).as("no attempt from " + from).isEmpty();
        assertThat(events(id, reason)).as(from.name()).singleElement()
                .satisfies(e -> assertThat(e.payloadJson()).isEqualTo("{\"from\":\"" + from.name() + "\"}"));
        assertThat(events(id, "escalated")).as(from.name()).hasSize(1);
        assertThat(events(id, "transition")).as(from.name()).singleElement().satisfies(e -> assertThat(e.payloadJson())
                .contains("\"from\":\"" + from.name() + "\"").contains("\"to\":\"ESCALATED\""));
    }

    // ---------------------------------------------------------------- propose

    // Covers: I12, 26.5, 7.2 (PROPOSED policy_denied with rule id I12): an entry that is not owned is denied before the
    // PolicyEngine is asked; nothing is sent, no attempt is written, and the policy_decision event names rule I12
    @Test
    void propose_atAnUnownedEntry_isDeniedWithI12_beforeThePolicyEngine() {
        entryIs(Optional.of(UNOWNED));
        lab.policyOverride = NEVER;
        Lab.Worker w = lab.worker("worker-a");

        ProposalResult result = lab.propose(w, TWO_SHIRTS);

        assertThat(result.state()).isEqualTo(IntentState.REJECTED);
        assertThat(result.quote()).isEmpty();
        PurchaseIntent stored = lab.intent(result.intentId());
        assertThat(stored.failureReason()).contains("policy_I12");
        assertThat(stored.merchantFingerprint()).isEqualTo(UNOWNED.fingerprint());
        assertThat(calls()).isEmpty();
        assertThat(lab.intents.attempts(result.intentId())).isEmpty();
        assertThat(events(result.intentId(), "policy_decision")).singleElement().satisfies(e -> assertThat(e.payloadJson())
                .isEqualTo("{\"allowed\":false,\"reason\":\"merchant entry is not owned (I12)\",\"rule\":\"I12\","
                        + "\"stage\":\"proposal\"}"));
        assertThat(events(result.intentId(), "transition")).singleElement().satisfies(e -> assertThat(e.payloadJson())
                .contains("\"from\":\"PROPOSED\"").contains("\"to\":\"REJECTED\""));
    }

    // Covers: I12, 10 (an owned entry off loopback is quoted as before, and the intent stores that entry's fingerprint)
    @Test
    void propose_atAnOwnedEntry_quotes_andStoresItsFingerprint() {
        entryIs(Optional.of(OWNED));
        Lab.Worker w = lab.worker("worker-a");

        ProposalResult result = lab.propose(w, TWO_SHIRTS);

        assertThat(result.state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(lab.intent(result.intentId()).merchantFingerprint()).isEqualTo(OWNED.fingerprint());
        assertThat(calls()).extracting(CallRecord::call).containsExactly(MerchantCall.CREATE);
    }

    // Covers: I12, 9.1 P1 (a merchant the registry does not list has no entry: it is the PolicyEngine's P1, not I12, and
    // the intent stores the no-entry fingerprint)
    @Test
    void propose_atAMerchantWithNoEntry_isDeniedByP1() {
        entryIs(Optional.of(OWNED));
        Lab.Worker w = lab.worker("worker-a");

        ProposalResult result = lab.proposeAt(w, new MerchantId("unlisted"), TWO_SHIRTS);

        assertThat(result.state()).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(result.intentId()).failureReason()).contains("policy_P1");
        assertThat(lab.intent(result.intentId()).merchantFingerprint()).isEqualTo("{}");
        assertThat(calls()).isEmpty();
    }

    // Covers: I12, 7.2 (an intent stored in PROPOSED by other code, at an unowned entry it was stored under, is denied with
    // I12 by its step, before the PolicyEngine)
    @Test
    void aProposedIntentAtAnUnownedEntry_isDeniedWithI12_byItsStep() {
        entryIs(Optional.of(UNOWNED));
        lab.policyOverride = NEVER;
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = stored("p1", IntentState.PROPOSED, UNOWNED.fingerprint(), false, Optional.empty());

        w.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.REJECTED);
        assertThat(lab.intent(id).failureReason()).contains("policy_I12");
        assertThat(calls()).isEmpty();
        assertThat(events(id, "policy_decision")).singleElement()
                .satisfies(e -> assertThat(e.payloadJson()).contains("\"rule\":\"I12\""));
        assertThat(events(id, "transition")).singleElement().satisfies(e -> assertThat(e.payloadJson())
                .contains("\"from\":\"PROPOSED\"").contains("\"to\":\"REJECTED\""));
    }

    // ---------------------------------------------------------------- later steps

    // Covers: I12, 7.2 (merchant_changed), 10, 19: in every non-terminal state, an entry whose fingerprint differs from the
    // stored one takes the intent to ESCALATED at the start of the step, sending nothing and writing no attempt
    @ParameterizedTest
    @EnumSource(value = IntentState.class, names = {"PROPOSED", "QUOTING", "AWAITING_APPROVAL", "APPROVED", "COMPLETING",
            "RECONCILING"})
    void anEntryChangedUnderTheIntent_escalates_sendingNothing(IntentState state) {
        entryIs(Optional.of(CHANGED));
        lab.policyOverride = NEVER;
        Lab.Worker w = lab.worker("worker-a");
        Optional<SessionId> session = state == IntentState.PROPOSED || state == IntentState.QUOTING ? Optional.empty()
                : Optional.of(new SessionId("s1"));
        IntentId id = stored("c1", state, OWNED.fingerprint(), false, session);

        w.orchestrator().advance(id);

        assertEscalated(id, "merchant_changed", state);
    }

    // Covers: I12, 7.2 (merchant_changed), 19 (a merchant removed from the registry is a change: the intent escalates)
    @Test
    void anEntryRemovedUnderTheIntent_escalates() {
        entryIs(Optional.empty());
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = stored("c1", IntentState.QUOTING, OWNED.fingerprint(), false, Optional.empty());

        w.orchestrator().advance(id);

        assertEscalated(id, "merchant_changed", IntentState.QUOTING);
    }

    // Covers: I12, 7.2 (merchant_not_owned): a create at an entry that fails I12 (here the one the intent was stored
    // under, so the fingerprint matches) escalates at the send, before the attempt row, and sends nothing
    @Test
    void aCreateAtAnUnownedEntry_escalates_sendingNothing() {
        entryIs(Optional.of(UNOWNED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = stored("n1", IntentState.QUOTING, UNOWNED.fingerprint(), false, Optional.empty());

        w.orchestrator().advance(id);

        assertEscalated(id, "merchant_not_owned", IntentState.QUOTING);
    }

    /** Entries that are {@code first} for the next lookup and {@code then} for every later one: an entry that changes within a step. */
    private void entryChangesAfterOneLookup(MerchantEntry first, MerchantEntry then) {
        java.util.concurrent.atomic.AtomicInteger lookups = new java.util.concurrent.atomic.AtomicInteger();
        lab.entries = id -> Optional.of(lookups.getAndIncrement() == 0 ? first : then);
    }

    private long sent(MerchantCall call) {
        return calls().stream().filter(c -> c.call() == call).count();
    }

    // Covers: I12, N1, 7.2: the check is made again at the send itself, not only at the start of the step: an entry that
    // stops being owned between the two leaves the first complete unsent, and no complete attempt written
    @Test
    void theFirstComplete_isCheckedAtTheSend() {
        entryIs(Optional.of(OWNED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        int before = lab.intents.attempts(id).size();

        entryChangesAfterOneLookup(OWNED, UNOWNED);
        w.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("merchant_changed");
        assertThat(sent(MerchantCall.COMPLETE)).isZero();
        assertThat(lab.intents.attempts(id)).hasSize(before);
        // The escalated event lists the attempts so far, for the human who looks (here the quote's create).
        assertThat(events(id, "escalated")).singleElement().satisfies(e -> assertThat(e.payloadJson())
                .contains("\"op\":\"CREATE\"").contains("\"revision\":0").contains("\"status\":\"RESPONDED\""));
    }

    // Covers: I12, 7.2 (the fulfillment selection's update, 7.2 QUOTING, is checked at its send too: an entry that
    // changes after the step's first look leaves the update unsent and writes no attempt)
    @Test
    void theSelectionUpdate_isCheckedAtTheSend() {
        lab.merchant.enableFulfillment();
        entryIs(Optional.of(OWNED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        assertThat(lab.intent(id).state()).as("a selection is pending").isEqualTo(IntentState.QUOTING);
        int before = lab.intents.attempts(id).size();

        entryChangesAfterOneLookup(OWNED, UNOWNED);
        w.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("merchant_changed");
        assertThat(sent(MerchantCall.UPDATE)).isZero();
        assertThat(lab.intents.attempts(id)).hasSize(before);
    }

    /** Drives the intent until reconciliation has approved a resend: RECONCILING -> COMPLETING, the resend not yet sent. */
    private IntentId resendApproved(Lab.Worker w) {
        lab.merchant.addRule(dev.ucplab.core.merchant.fake.FaultRule.nth(MerchantCall.COMPLETE, 1,
                new dev.ucplab.core.merchant.fake.Fault.NotSent()));
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        for (int i = 0; i < 50 && !reconciledToCompleting(id); i++) {
            lab.waitUntilNextAttempt(w.orchestrator().advance(id));
        }
        assertThat(reconciledToCompleting(id)).as("RECONCILING approved a resend").isTrue();
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.COMPLETING);
        assertThat(sent(MerchantCall.COMPLETE)).isEqualTo(1);
        return id;
    }

    private boolean reconciledToCompleting(IntentId id) {
        return events(id, "transition").stream().anyMatch(e -> e.payloadJson().contains("\"from\":\"RECONCILING\"")
                && e.payloadJson().contains("\"to\":\"COMPLETING\""));
    }

    // Covers: I12, N1, 7.2, 7.5 (the RECONCILING ready_for_complete row resends complete): an entry that changes after
    // reconciliation approved the resend leaves the resend unsent, and no attempt written
    @Test
    void theResendApprovedByReconciliation_isNotSent_whenTheEntryChanged() {
        entryIs(Optional.of(OWNED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = resendApproved(w);
        int before = lab.intents.attempts(id).size();

        entryIs(Optional.of(UNOWNED));
        w.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("merchant_changed");
        assertThat(sent(MerchantCall.COMPLETE)).isEqualTo(1);
        assertThat(lab.intents.attempts(id)).hasSize(before);
    }

    // Covers: I12, N1, 7.2, 7.5: the same resend, with the entry changing between the start of the step and the send:
    // the send's own check stops it
    @Test
    void theResendApprovedByReconciliation_isCheckedAtTheSend() {
        entryIs(Optional.of(OWNED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = resendApproved(w);
        int before = lab.intents.attempts(id).size();

        entryChangesAfterOneLookup(OWNED, UNOWNED);
        w.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("merchant_changed");
        assertThat(sent(MerchantCall.COMPLETE)).isEqualTo(1);
        assertThat(lab.intents.attempts(id)).hasSize(before);
    }

    // Covers: I12, 7.2: the create of a quote is checked at the send too (the entry changes after the step's first look)
    @Test
    void aCreate_isCheckedAtTheSend() {
        IntentId id = stored("q1", IntentState.QUOTING, OWNED.fingerprint(), false, Optional.empty());
        entryChangesAfterOneLookup(OWNED, UNOWNED);
        Lab.Worker w = lab.worker("worker-a");

        w.orchestrator().advance(id);

        assertEscalated(id, "merchant_changed", IntentState.QUOTING);
    }

    // Covers: I12, 7.2 (merchant_not_owned is for a step that would send: an AWAITING_APPROVAL step with nothing due sends
    // nothing and is left alone)
    @Test
    void anAwaitingStepAtAnUnownedEntry_isNotEscalated() {
        entryIs(Optional.of(UNOWNED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = stored("n1", IntentState.AWAITING_APPROVAL, UNOWNED.fingerprint(), false,
                Optional.of(new SessionId("s1")));

        w.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.AWAITING_APPROVAL);
        assertThat(lab.intent(id).failureReason()).isEmpty();
        assertThat(lab.events(id)).isEmpty();
    }

    // Covers: I12, N1, 7.2 (merchant_changed), 19: an intent approved under an owned entry, whose entry then stops being
    // owned (a restart with another file), is never sent its complete
    @Test
    void anApprovedIntent_whoseEntryStopsBeingOwned_isNeverSentItsComplete() {
        entryIs(Optional.of(OWNED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, true);
        assertThat(lab.intent(id).state()).isEqualTo(IntentState.APPROVED);
        int before = lab.intents.attempts(id).size();

        entryIs(Optional.of(UNOWNED));
        restarted().orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(id).failureReason()).contains("merchant_changed");
        assertThat(calls()).extracting(CallRecord::call).doesNotContain(MerchantCall.COMPLETE);
        assertThat(lab.intents.attempts(id)).hasSize(before);
    }

    // Covers: I12, 7.2 (a terminal intent with nothing owed is idle whatever its entry: the escalation row is for
    // non-terminal states only)
    @Test
    void aTerminalIntentWithNothingOwed_isIdle_evenWhenItsEntryChanged() {
        entryIs(Optional.of(CHANGED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId done = stored("t1", IntentState.ESCALATED, OWNED.fingerprint(), false, Optional.of(new SessionId("s1")));
        IntentId rejected = stored("t2", IntentState.REJECTED, OWNED.fingerprint(), false, Optional.of(new SessionId("s2")));

        assertThat(w.orchestrator().advance(done).idle()).isTrue();
        assertThat(w.orchestrator().advance(rejected).idle()).isTrue();

        assertThat(lab.intent(done).state()).isEqualTo(IntentState.ESCALATED);
        assertThat(lab.intent(rejected).state()).isEqualTo(IntentState.REJECTED);
        assertThat(lab.events(done)).isEmpty();
        assertThat(lab.events(rejected)).isEmpty();
    }

    // ---------------------------------------------------------------- the owed cancel (7.6)

    // Covers: I12, 7.6 (cancel_blocked_i12), 19: a rejected intent owes a cancel, and its entry changes before it is
    // sent; the cancel is not sent, no attempt is written, the flag is cleared and the state stays
    @Test
    void anOwedCancel_atAChangedEntry_isNotSent_andTheFlagIsCleared() {
        entryIs(Optional.of(OWNED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, false);
        assertThat(lab.intent(id).cancelPending()).isTrue();
        int before = lab.intents.attempts(id).size();

        entryIs(Optional.of(CHANGED));
        restarted().orchestrator().advance(id);

        PurchaseIntent after = lab.intent(id);
        assertThat(after.state()).isEqualTo(IntentState.REJECTED);
        assertThat(after.cancelPending()).isFalse();
        assertThat(calls()).extracting(CallRecord::call).doesNotContain(MerchantCall.CANCEL);
        assertThat(lab.intents.attempts(id)).hasSize(before);
        assertThat(events(id, "cancel_blocked_i12")).singleElement()
                .satisfies(e -> assertThat(e.payloadJson()).isEqualTo("{}"));
        assertThat(events(id, "cancel_sent")).isEmpty();
    }

    // Covers: I12, 7.6 (cancel_blocked_i12): an owed cancel at an entry that fails I12 is not sent even when the entry is
    // the one the intent was stored under
    @Test
    void anOwedCancel_atAnUnownedEntry_isNotSent() {
        entryIs(Optional.of(UNOWNED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = stored("x1", IntentState.FAILED_NOT_CHARGED, UNOWNED.fingerprint(), true,
                Optional.of(new SessionId("s1")));

        w.orchestrator().advance(id);

        assertThat(lab.intent(id).state()).isEqualTo(IntentState.FAILED_NOT_CHARGED);
        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(calls()).isEmpty();
        assertThat(lab.intents.attempts(id)).isEmpty();
        assertThat(events(id, "cancel_blocked_i12")).hasSize(1);
    }

    // Covers: I12, 7.6 (the orphan-session probe re-sends a create: at a changed entry it is blocked like the cancel)
    @Test
    void anOrphanProbe_atAChangedEntry_sendsNoCreate() {
        entryIs(Optional.of(CHANGED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = stored("x1", IntentState.REJECTED, OWNED.fingerprint(), true, Optional.empty());

        w.orchestrator().advance(id);

        assertThat(lab.intent(id).cancelPending()).isFalse();
        assertThat(calls()).isEmpty();
        assertThat(events(id, "cancel_blocked_i12")).hasSize(1);
    }

    // Covers: 7.6, I12 (the control: at an unchanged, owned entry the owed cancel is sent as before)
    @Test
    void anOwedCancel_atAnUnchangedOwnedEntry_isSent() {
        entryIs(Optional.of(OWNED));
        Lab.Worker w = lab.worker("worker-a");
        IntentId id = lab.propose(w, TWO_SHIRTS).intentId();
        lab.decide(w, id, false);

        w.orchestrator().advance(id);

        assertThat(calls()).extracting(CallRecord::call).contains(MerchantCall.CANCEL);
        assertThat(events(id, "cancel_blocked_i12")).isEmpty();
    }
}
