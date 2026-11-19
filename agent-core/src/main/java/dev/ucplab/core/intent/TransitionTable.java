package dev.ucplab.core.intent;

import static dev.ucplab.core.intent.IntentState.APPROVED;
import static dev.ucplab.core.intent.IntentState.AWAITING_APPROVAL;
import static dev.ucplab.core.intent.IntentState.COMPLETED;
import static dev.ucplab.core.intent.IntentState.COMPLETING;
import static dev.ucplab.core.intent.IntentState.ESCALATED;
import static dev.ucplab.core.intent.IntentState.FAILED_NOT_CHARGED;
import static dev.ucplab.core.intent.IntentState.PROPOSED;
import static dev.ucplab.core.intent.IntentState.QUOTING;
import static dev.ucplab.core.intent.IntentState.RECONCILING;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Table 7.2 [CONTRACT]. A row that lists alternative events is one entry per event. The
 * requote-cap row ("COMPLETING or RECONCILING") pairs each state with the events it can receive:
 * COMPLETING with price_changed and checkout incomplete, RECONCILING with observed incomplete.
 */
public final class TransitionTable {

    private static final List<Transition> ROWS = List.of(
            row(PROPOSED, TransitionEvent.POLICY_OK, TransitionGuard.POLICY_ALLOWS, QUOTING, SideEffect.NONE),
            row(PROPOSED, TransitionEvent.POLICY_DENIED, TransitionGuard.NONE, IntentState.REJECTED, SideEffect.RECORD_POLICY_RULE),
            row(QUOTING, TransitionEvent.QUOTE_READY, TransitionGuard.QUOTE_POLICY_ALLOWS, AWAITING_APPROVAL, SideEffect.CREATE_APPROVAL),
            row(QUOTING, TransitionEvent.ITEM_UNAVAILABLE, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.CANCEL_SESSION_BEST_EFFORT),
            row(QUOTING, TransitionEvent.OUT_OF_STOCK, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.CANCEL_SESSION_BEST_EFFORT),
            row(QUOTING, TransitionEvent.NOT_SENT, TransitionGuard.RETRIES_LEFT, QUOTING, SideEffect.RESEND_SAME_KEY),
            row(QUOTING, TransitionEvent.UNCERTAIN, TransitionGuard.RETRIES_LEFT, QUOTING, SideEffect.RESEND_SAME_KEY),
            // Cancel when a session id is known (Transition.owesCancel + PurchaseIntent.moved set cancel_pending then).
            row(QUOTING, TransitionEvent.RETRIES_EXHAUSTED, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.CANCEL_SESSION_BEST_EFFORT),
            row(AWAITING_APPROVAL, TransitionEvent.APPROVED, TransitionGuard.APPROVAL_VALID, APPROVED, SideEffect.CONSUME_APPROVAL),
            row(AWAITING_APPROVAL, TransitionEvent.REJECTED, TransitionGuard.NONE, IntentState.REJECTED, SideEffect.CANCEL_SESSION),
            row(AWAITING_APPROVAL, TransitionEvent.EXPIRED, TransitionGuard.NONE, IntentState.REJECTED, SideEffect.CANCEL_SESSION),
            row(APPROVED, TransitionEvent.SEND_COMPLETE, TransitionGuard.ATTEMPT_PERSISTED, COMPLETING, SideEffect.POST_COMPLETE),
            row(COMPLETING, TransitionEvent.MERCHANT_COMPLETED, TransitionGuard.NONE, COMPLETED, SideEffect.STORE_ORDER_ID),
            row(COMPLETING, TransitionEvent.PAYMENT_DECLINED, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.CANCEL_SESSION),
            row(COMPLETING, TransitionEvent.PRICE_CHANGED, TransitionGuard.REQUOTE_BELOW_CAP, QUOTING, SideEffect.REQUOTE_ON_SAME_SESSION),
            row(COMPLETING, TransitionEvent.CHECKOUT_INCOMPLETE, TransitionGuard.REQUOTE_BELOW_CAP, QUOTING, SideEffect.REQUOTE_ON_SAME_SESSION),
            row(COMPLETING, TransitionEvent.UNCERTAIN, TransitionGuard.NONE, RECONCILING, SideEffect.NONE),
            row(RECONCILING, TransitionEvent.OBSERVED_COMPLETED, TransitionGuard.NONE, COMPLETED, SideEffect.STORE_ORDER_ID),
            row(RECONCILING, TransitionEvent.OBSERVED_COMPLETE_IN_PROGRESS, TransitionGuard.NONE, RECONCILING, SideEffect.POLL_AGAIN),
            row(RECONCILING, TransitionEvent.OBSERVED_READY_FOR_COMPLETE, TransitionGuard.RESEND_ALLOWED, COMPLETING, SideEffect.RESEND_COMPLETE),
            row(RECONCILING, TransitionEvent.OBSERVED_CANCELED, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.NONE),
            row(RECONCILING, TransitionEvent.OBSERVED_INCOMPLETE, TransitionGuard.REQUOTE_BELOW_CAP, QUOTING, SideEffect.REQUOTE_ON_SAME_SESSION),
            row(RECONCILING, TransitionEvent.DEADLINE_REACHED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(COMPLETING, TransitionEvent.PRICE_CHANGED, TransitionGuard.REQUOTE_CAP_REACHED, IntentState.REJECTED, SideEffect.REJECT_PRICE_UNSTABLE),
            row(COMPLETING, TransitionEvent.CHECKOUT_INCOMPLETE, TransitionGuard.REQUOTE_CAP_REACHED, IntentState.REJECTED, SideEffect.REJECT_PRICE_UNSTABLE),
            row(RECONCILING, TransitionEvent.OBSERVED_INCOMPLETE, TransitionGuard.REQUOTE_CAP_REACHED, IntentState.REJECTED, SideEffect.REJECT_PRICE_UNSTABLE),
            row(RECONCILING, TransitionEvent.OBSERVED_READY_FOR_COMPLETE, TransitionGuard.QUIET_PERIOD_NOT_ELAPSED, RECONCILING, SideEffect.POLL_AGAIN),
            // Defensive: a correct orchestrator never reaches this, but it must never throw here.
            row(QUOTING, TransitionEvent.OBSERVED_COMPLETED, TransitionGuard.NONE, RECONCILING, SideEffect.NONE),
            row(QUOTING, TransitionEvent.OBSERVED_COMPLETE_IN_PROGRESS, TransitionGuard.NONE, RECONCILING, SideEffect.NONE),
            // A create with every item unavailable has no session: nothing to cancel.
            row(QUOTING, TransitionEvent.CREATE_REJECTED, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.NONE),
            // A readable answer the agent cannot use (quote_unsupported at a handoff merchant, fulfillment_unsupported):
            // the merchant answered, so nothing is retried; its session is cancelled.
            row(QUOTING, TransitionEvent.QUOTE_UNSUPPORTED, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.CANCEL_SESSION_BEST_EFFORT),
            // A refused update: the session exists, so its cancel is owed (8.1, error envelope).
            row(QUOTING, TransitionEvent.UPDATE_REJECTED, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.CANCEL_SESSION_BEST_EFFORT),
            // A ProtocolError on complete does not settle the outcome: reconcile, never FAILED_NOT_CHARGED.
            row(COMPLETING, TransitionEvent.PROTOCOL_ERROR, TransitionGuard.NONE, RECONCILING, SideEffect.NONE),
            // The approval behind an APPROVED intent is not usable (I3): nothing is sent, a human must look.
            row(APPROVED, TransitionEvent.APPROVAL_INVALID, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            // I12: the entry fails I12 at a step that would send, or changed under the intent. Any non-terminal state;
            // nothing is sent and no attempt written.
            row(PROPOSED, TransitionEvent.MERCHANT_NOT_OWNED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(QUOTING, TransitionEvent.MERCHANT_NOT_OWNED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(AWAITING_APPROVAL, TransitionEvent.MERCHANT_NOT_OWNED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(APPROVED, TransitionEvent.MERCHANT_NOT_OWNED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(COMPLETING, TransitionEvent.MERCHANT_NOT_OWNED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(RECONCILING, TransitionEvent.MERCHANT_NOT_OWNED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(PROPOSED, TransitionEvent.MERCHANT_CHANGED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(QUOTING, TransitionEvent.MERCHANT_CHANGED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(AWAITING_APPROVAL, TransitionEvent.MERCHANT_CHANGED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(APPROVED, TransitionEvent.MERCHANT_CHANGED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(COMPLETING, TransitionEvent.MERCHANT_CHANGED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(RECONCILING, TransitionEvent.MERCHANT_CHANGED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            // The merchant's transport offers no complete (8.4, N1): nothing was sent, so not charged; checked before
            // the write-ahead, so no complete attempt is written and nothing reconciles.
            row(APPROVED, TransitionEvent.COMPLETE_UNAVAILABLE, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.CANCEL_SESSION),
            // Handoff merchants (7.2, 19): QUOTE_READY serves quote_ready (handoff) under its own condition; APPROVED
            // leads only to these rows, never to SEND_COMPLETE.
            row(QUOTING, TransitionEvent.REQUIRES_ESCALATION, TransitionGuard.QUOTE_POLICY_ALLOWS, AWAITING_APPROVAL, SideEffect.CREATE_APPROVAL),
            row(APPROVED, TransitionEvent.HAND_OFF, TransitionGuard.NONE, IntentState.HANDED_OFF, SideEffect.STORE_HANDOFF_URL),
            row(APPROVED, TransitionEvent.HAND_OFF_CHANGED, TransitionGuard.REQUOTE_BELOW_CAP, QUOTING, SideEffect.REQUOTE_ON_SAME_SESSION),
            row(APPROVED, TransitionEvent.HAND_OFF_CHANGED, TransitionGuard.REQUOTE_CAP_REACHED, IntentState.REJECTED, SideEffect.REJECT_PRICE_UNSTABLE),
            row(APPROVED, TransitionEvent.HAND_OFF_GONE, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.NONE),
            // The hand_off read was not sent or is uncertain: try again on the next pass (7.6 deadline applies).
            row(APPROVED, TransitionEvent.NOT_SENT, TransitionGuard.NONE, APPROVED, SideEffect.NONE),
            row(APPROVED, TransitionEvent.UNCERTAIN, TransitionGuard.NONE, APPROVED, SideEffect.NONE),
            row(APPROVED, TransitionEvent.HAND_OFF_REFUSED, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.CANCEL_SESSION),
            row(APPROVED, TransitionEvent.HAND_OFF_UNEXPECTED, TransitionGuard.NONE, ESCALATED, SideEffect.ESCALATION_EVENT),
            row(APPROVED, TransitionEvent.HANDOFF_TIMEOUT, TransitionGuard.NONE, IntentState.REJECTED, SideEffect.CANCEL_SESSION),
            // Link merchants (7.2, 26.7): QUOTE_READY, ITEM_UNAVAILABLE, RETRIES_EXHAUSTED and HAND_OFF serve the (link)
            // rows under their own conditions; a link intent has no session, so nothing is cancelled.
            row(QUOTING, TransitionEvent.LINK_REFUSED, TransitionGuard.NONE, FAILED_NOT_CHARGED, SideEffect.NONE));

    private TransitionTable() {
    }

    /** Every entry, in table order. */
    public static List<Transition> rows() {
        return ROWS;
    }

    /**
     * Finds the single entry for {@code from} and {@code event} whose guard is NONE or is in
     * {@code holdingGuards}.
     *
     * @throws IllegalTransitionException if the table has no entry for the pair, no entry's guard
     *     holds, or more than one entry's guard holds
     */
    public static Transition resolve(IntentState from, TransitionEvent event, Set<TransitionGuard> holdingGuards) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(holdingGuards, "holdingGuards");

        List<Transition> candidates = ROWS.stream()
                .filter(t -> t.from() == from && t.event() == event)
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalTransitionException(from, event, "not in table 7.2");
        }
        List<Transition> matching = candidates.stream()
                .filter(t -> t.guard() == TransitionGuard.NONE || holdingGuards.contains(t.guard()))
                .toList();
        if (matching.isEmpty()) {
            throw new IllegalTransitionException(from, event, "no guard holds among "
                    + candidates.stream().map(Transition::guard).toList());
        }
        if (matching.size() > 1) {
            throw new IllegalTransitionException(from, event, "ambiguous: guards "
                    + matching.stream().map(Transition::guard).toList() + " all hold");
        }
        return matching.get(0);
    }

    private static Transition row(
            IntentState from, TransitionEvent event, TransitionGuard guard, IntentState to, SideEffect effect) {
        return new Transition(from, event, guard, to, effect);
    }
}
