package dev.ucplab.core.intent;

/**
 * The Guard column of table 7.2. The orchestrator evaluates guards; the table only says which
 * guard a row needs. {@link #NONE} always holds.
 */
public enum TransitionGuard {
    NONE,
    /** PROPOSED, policy_ok: PolicyEngine allows. */
    POLICY_ALLOWS,
    /** QUOTING, quote_ready: policy allows the quoted items and total. */
    QUOTE_POLICY_ALLOWS,
    /** QUOTING, not_sent or uncertain: retries left. */
    RETRIES_LEFT,
    /** AWAITING_APPROVAL, approved: token valid, hash matches, unexpired, unconsumed (I3). */
    APPROVAL_VALID,
    /** APPROVED, send_complete: attempt row persisted first (7.3). */
    ATTEMPT_PERSISTED,
    /** Re-quote rows: requote_count is below the P8 cap. */
    REQUOTE_BELOW_CAP,
    /** Re-quote rows: requote_count has reached the P8 cap. */
    REQUOTE_CAP_REACHED,
    /** RECONCILING, observed ready_for_complete: resends below the maximum and the quiet period (7.5) has elapsed. */
    RESEND_ALLOWED,
    /** RECONCILING, observed ready_for_complete: quiet period (7.5) not yet elapsed. */
    QUIET_PERIOD_NOT_ELAPSED
}
